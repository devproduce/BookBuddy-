package com.bookbuddy.ingestion;

import com.bookbuddy.book.BookDocument;
import com.bookbuddy.book.BookRepository;
import com.bookbuddy.book.BookService;
import com.bookbuddy.common.ApiException;
import com.bookbuddy.config.AsyncConfig;
import com.bookbuddy.config.BookBuddyProperties;
import com.bookbuddy.document.ChunkingService;
import com.bookbuddy.document.PageText;
import com.bookbuddy.document.PdfExtractionService;
import com.bookbuddy.document.PdfExtractionService.ExtractionResult;
import com.bookbuddy.document.PdfExtractionService.PdfExtractionException;
import com.bookbuddy.document.TextChunk;
import com.bookbuddy.document.TextCleaningService;
import com.bookbuddy.document.TextCleaningService.LanguageGuess;
import com.bookbuddy.embedding.EmbeddingProvider;
import com.bookbuddy.embedding.VectorMath;
import com.bookbuddy.ingestion.IngestionDtos.BookStatusResponse;
import com.bookbuddy.ingestion.IngestionDtos.ChunkPageResponse;
import com.bookbuddy.ingestion.IngestionJobDocument.Stage;
import com.bookbuddy.vector.BookChunkDocument;
import com.bookbuddy.vector.BookChunkRepository;
import com.bookbuddy.vector.BookChunkWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orchestrates PDF ingestion: EXTRACTING -> CHUNKING -> EMBEDDING -> COMPLETED | FAILED | NEEDS_OCR (or CANCELLED).
 *
 * <p>The uploaded PDF is stored under {@code uploadDir/<bookId>.pdf} only until the job finishes; it is always
 * deleted in a {@code finally} block, whatever the outcome. Chunks are upserted by (bookId, chunkIndex) so re-running
 * a job is idempotent.
 */
@Service
public class IngestionJobService {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobService.class);

    private static final int EMBED_BATCH_SIZE = 16;
    private static final Duration STALE_UPLOAD_AGE = Duration.ofHours(24);

    private final BookRepository bookRepository;
    private final IngestionJobRepository jobRepository;
    private final BookChunkRepository chunkRepository;
    private final BookChunkWriter chunkWriter;
    private final BookService bookService;
    private final PdfExtractionService pdfExtraction;
    private final TextCleaningService cleaning;
    private final ChunkingService chunking;
    private final EmbeddingProvider embeddings;
    private final BookBuddyProperties properties;
    private final Executor executor;

    /** Job ids currently being executed by this JVM. */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** Job ids with a pending in-memory cancel request (avoids losing the flag to a concurrent job save). */
    private final Set<String> cancelRequests = ConcurrentHashMap.newKeySet();

    public IngestionJobService(
            BookRepository bookRepository,
            IngestionJobRepository jobRepository,
            BookChunkRepository chunkRepository,
            BookChunkWriter chunkWriter,
            BookService bookService,
            PdfExtractionService pdfExtraction,
            TextCleaningService cleaning,
            ChunkingService chunking,
            EmbeddingProvider embeddings,
            BookBuddyProperties properties,
            @Qualifier(AsyncConfig.INGESTION_EXECUTOR) Executor executor) {
        this.bookRepository = bookRepository;
        this.jobRepository = jobRepository;
        this.chunkRepository = chunkRepository;
        this.chunkWriter = chunkWriter;
        this.bookService = bookService;
        this.pdfExtraction = pdfExtraction;
        this.cleaning = cleaning;
        this.chunking = chunking;
        this.embeddings = embeddings;
        this.properties = properties;
        this.executor = executor;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------------------

    /** Stores the PDF temporarily (verifying it is a PDF whose SHA-256 matches the book). Does not start processing. */
    public BookStatusResponse upload(String userId, String bookId, MultipartFile file) {
        BookDocument book = findBook(userId, bookId);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_EMPTY", "The uploaded file is empty");
        }
        if (isJobRunning(userId, bookId)) {
            throw ApiException.conflict("INGESTION_IN_PROGRESS", "This book is being processed right now");
        }

        Path dir = uploadDir();
        Path part = dir.resolve(bookId + ".pdf.part");
        Path target = pendingFile(bookId);
        try {
            Files.createDirectories(dir);
            MessageDigest sha = sha256();
            try (InputStream in = new DigestInputStream(file.getInputStream(), sha)) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!looksLikePdf(part)) {
                throw ApiException.badRequest("NOT_A_PDF", "The uploaded file is not a PDF");
            }
            String actual = HexFormat.of().formatHex(sha.digest());
            if (book.getContentHash() != null && !book.getContentHash().equalsIgnoreCase(actual)) {
                throw ApiException.badRequest(
                        "FILE_HASH_MISMATCH", "The uploaded file does not match the book's content hash");
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Could not store upload for book {}", bookId, e);
            throw new ApiException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "UPLOAD_FAILED",
                    "Could not store the uploaded file",
                    true,
                    null);
        } finally {
            deleteQuietly(part);
        }

        if (book.getStatus() == BookDocument.Status.FAILED || book.getStatus() == BookDocument.Status.NEEDS_OCR) {
            mutateBook(bookId, b -> {
                b.setStatus(BookDocument.Status.QUEUED);
                b.setFailureReason(null);
            });
        }
        return status(userId, bookId);
    }

    /** Starts (or restarts) processing of the previously uploaded PDF. Idempotent while a job is running. */
    public BookStatusResponse start(String userId, String bookId) {
        return launch(userId, bookId, false);
    }

    /** Re-runs a job that was interrupted or did not complete; requires an existing, unfinished job. */
    public BookStatusResponse resume(String userId, String bookId) {
        return launch(userId, bookId, true);
    }

    public BookStatusResponse cancel(String userId, String bookId) {
        findBook(userId, bookId);
        Optional<IngestionJobDocument> latest = jobRepository.findFirstByBookIdAndUserIdOrderByCreatedAtDesc(bookId, userId);
        if (latest.isEmpty() || !latest.get().getStage().isActive()) {
            return status(userId, bookId);
        }
        IngestionJobDocument job = latest.get();
        cancelRequests.add(job.getId());
        if (running.contains(job.getId())) {
            updateJob(job.getId(), j -> {
                j.setCancelRequested(true);
                j.setMessage("Cancelling");
            });
        } else {
            finishCancelled(job.getId(), bookId, true); // stale job nobody is working on
            deleteQuietly(pendingFile(bookId));
            cancelRequests.remove(job.getId());
        }
        return status(userId, bookId);
    }

    public BookStatusResponse status(String userId, String bookId) {
        BookDocument book = findBook(userId, bookId);
        Optional<IngestionJobDocument> job = jobRepository.findFirstByBookIdAndUserIdOrderByCreatedAtDesc(bookId, userId);
        boolean uploaded = Files.isRegularFile(pendingFile(bookId));
        if (job.isPresent()) {
            IngestionJobDocument j = job.get();
            return new BookStatusResponse(
                    bookId,
                    book.getStatus(),
                    j.getStage(),
                    j.getProgress(),
                    j.getMessage(),
                    book.getFailureReason() != null ? book.getFailureReason() : j.getFailureReason(),
                    book.getChunkCount(),
                    book.getPageCount(),
                    j.getId(),
                    uploaded,
                    j.getUpdatedAt() != null ? j.getUpdatedAt() : book.getUpdatedAt());
        }
        return new BookStatusResponse(
                bookId,
                book.getStatus(),
                stageOf(book.getStatus()),
                book.getStatus() == BookDocument.Status.COMPLETED ? 100 : 0,
                null,
                book.getFailureReason(),
                book.getChunkCount(),
                book.getPageCount(),
                null,
                uploaded,
                book.getUpdatedAt());
    }

    public ChunkPageResponse listChunks(String userId, String bookId, int page, int size) {
        findBook(userId, bookId);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 50);
        return ChunkPageResponse.from(chunkRepository.findPageByBookIdAndUserId(
                bookId, userId, PageRequest.of(safePage, safeSize, Sort.by("chunkIndex"))));
    }

    /** Deletes the book together with its chunks, ingestion jobs and any pending upload. */
    public void deleteBookCascade(String userId, String bookId) {
        findBook(userId, bookId); // ownership / 404
        jobRepository.findFirstByBookIdAndUserIdOrderByCreatedAtDesc(bookId, userId)
                .filter(j -> j.getStage().isActive())
                .ifPresent(j -> cancelRequests.add(j.getId()));
        chunkWriter.deleteByBook(bookId);
        jobRepository.deleteByBookId(bookId);
        deleteQuietly(pendingFile(bookId));
        bookService.delete(userId, bookId);
        chunkWriter.deleteByBook(bookId); // a running job may have written a batch in between
    }

    // ---------------------------------------------------------------------------------------------------------
    // Job execution
    // ---------------------------------------------------------------------------------------------------------

    private BookStatusResponse launch(String userId, String bookId, boolean requireExistingJob) {
        BookDocument book = findBook(userId, bookId);
        Optional<IngestionJobDocument> latest = jobRepository.findFirstByBookIdAndUserIdOrderByCreatedAtDesc(bookId, userId);

        if (latest.isPresent() && latest.get().getStage().isActive() && running.contains(latest.get().getId())) {
            return status(userId, bookId); // already running: idempotent
        }
        if (requireExistingJob && (latest.isEmpty() || latest.get().getStage() == Stage.COMPLETED)) {
            throw ApiException.conflict("NOTHING_TO_RESUME", "There is no unfinished processing job for this book");
        }
        if (!Files.isRegularFile(pendingFile(bookId))) {
            throw ApiException.conflict(
                    "FILE_REQUIRED", "Upload the PDF (POST /books/{id}/file) before processing this book");
        }

        IngestionJobDocument job;
        if (latest.isPresent() && latest.get().getStage().isActive()) {
            job = latest.get(); // stale (e.g. server restart): resume the same job
            job.setAttempt(job.getAttempt() + 1);
        } else {
            job = new IngestionJobDocument();
            job.setUserId(userId);
            job.setBookId(bookId);
            job.setAttempt(latest.map(j -> j.getAttempt() + 1).orElse(1));
        }
        job.setStage(Stage.QUEUED);
        job.setProgress(0);
        job.setMessage("Queued");
        job.setFailureReason(null);
        job.setCancelRequested(false);
        job.setStartedAt(null);
        job.setFinishedAt(null);
        job = jobRepository.save(job);
        cancelRequests.remove(job.getId());

        mutateBook(bookId, b -> {
            b.setStatus(BookDocument.Status.QUEUED);
            b.setFailureReason(null);
        });

        String jobId = job.getId();
        running.add(jobId);
        try {
            executor.execute(() -> run(jobId));
        } catch (RejectedExecutionException e) {
            running.remove(jobId);
            fail(jobId, bookId, "Server is busy, please try again shortly");
        }
        return status(userId, bookId);
    }

    /** Executes one job. Public so it can be driven directly (and synchronously) from tests. */
    public void run(String jobId) {
        IngestionJobDocument job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            running.remove(jobId);
            return;
        }
        String bookId = job.getBookId();
        Path pdf = pendingFile(bookId);
        try {
            updateJob(jobId, j -> j.setStartedAt(Instant.now()));
            process(job, pdf);
        } catch (IngestionCancelled c) {
            finishCancelled(jobId, bookId, !c.bookGone);
        } catch (PdfExtractionException e) {
            log.warn("Extraction failed for book {}: {}", bookId, e.getMessage());
            String reason = e.isEncrypted()
                    ? "This PDF is password protected or encrypted. Remove the protection and upload it again."
                    : e.isTooManyPages()
                            ? e.getMessage()
                            : "This file could not be read as a PDF. It may be corrupted.";
            fail(jobId, bookId, reason);
        } catch (Exception e) {
            log.error("Ingestion failed for book {}", bookId, e);
            fail(jobId, bookId, "Processing failed unexpectedly. Please try again.");
        } finally {
            deleteQuietly(pdf);
            running.remove(jobId);
            cancelRequests.remove(jobId);
        }
    }

    private void process(IngestionJobDocument job, Path pdf) {
        String jobId = job.getId();
        String bookId = job.getBookId();
        String userId = job.getUserId();
        BookBuddyProperties.Ingestion cfg = properties.ingestion();

        // ---- EXTRACTING
        checkCancelled(jobId, bookId);
        setStage(jobId, bookId, Stage.EXTRACTING, 5, "Reading pages");
        if (!Files.isRegularFile(pdf)) {
            throw new PdfExtractionException("Uploaded file is missing", false, false, null);
        }
        ExtractionResult extraction = pdfExtraction.extract(pdf, cfg.maxPages());
        updateJob(jobId, j -> j.setPagesTotal(extraction.pageCount()));
        double quality = extraction.fractionOfPagesWithText(cfg.minCharsPerPage());

        if (extraction.pageCount() == 0 || extraction.avgCharsPerPage() < cfg.minCharsPerPage()) {
            String reason = String.format(
                    "Only %.0f characters per page could be extracted; this looks like a scanned PDF that needs OCR.",
                    extraction.avgCharsPerPage());
            updateJob(jobId, j -> {
                j.setStage(Stage.NEEDS_OCR);
                j.setProgress(100);
                j.setMessage("Needs OCR");
                j.setFailureReason(reason);
                j.setFinishedAt(Instant.now());
            });
            mutateBook(bookId, b -> {
                b.setStatus(BookDocument.Status.NEEDS_OCR);
                b.setFailureReason(reason);
                b.setPageCount(extraction.pageCount());
                b.setExtractionQuality(quality);
            });
            return;
        }

        // ---- CHUNKING
        checkCancelled(jobId, bookId);
        setStage(jobId, bookId, Stage.CHUNKING, 30, "Cleaning and splitting text");
        List<PageText> pages = cleaning.cleanPages(
                extraction.pages().stream().map(PageText::text).toList());
        List<TextChunk> chunks = chunking.chunk(pages);
        if (chunks.isEmpty()) {
            fail(jobId, bookId, "No readable text was found in this PDF.");
            return;
        }
        LanguageGuess language = cleaning.detectLanguage(sample(pages));
        updateJob(jobId, j -> j.setChunksTotal(chunks.size()));

        // ---- EMBEDDING
        checkCancelled(jobId, bookId);
        setStage(jobId, bookId, Stage.EMBEDDING, 40, "Indexing passages");
        String embeddingVersion = embeddings.version();
        String embeddingModel = embeddings.modelName();
        int done = 0;
        for (int from = 0; from < chunks.size(); from += EMBED_BATCH_SIZE) {
            checkCancelled(jobId, bookId);
            List<TextChunk> batch = chunks.subList(from, Math.min(chunks.size(), from + EMBED_BATCH_SIZE));
            List<BookChunkDocument> docs = new ArrayList<>(batch.size());
            for (TextChunk c : batch) {
                docs.add(toDocument(userId, bookId, c, embeddingVersion, embeddingModel));
            }
            chunkWriter.upsertAll(docs);
            done += batch.size();
            int progress = 40 + (int) Math.round(55.0 * done / chunks.size());
            int embedded = done;
            updateJob(jobId, j -> {
                j.setProgress(progress);
                j.setChunksEmbedded(embedded);
            });
        }
        chunkWriter.deleteFromIndex(bookId, chunks.size()); // drop leftovers of an earlier, longer run

        // ---- COMPLETED
        checkCancelled(jobId, bookId);
        mutateBook(bookId, b -> {
            b.setStatus(BookDocument.Status.COMPLETED);
            b.setFailureReason(null);
            b.setChunkCount(chunks.size());
            b.setPageCount(extraction.pageCount());
            b.setLanguage(language.code());
            b.setLanguageConfidence(language.confidence());
            b.setExtractionQuality(quality);
            b.setChunkingVersion(ChunkingService.VERSION);
            b.setEmbeddingVersion(embeddingVersion);
        });
        updateJob(jobId, j -> {
            j.setStage(Stage.COMPLETED);
            j.setProgress(100);
            j.setMessage("Ready");
            j.setFailureReason(null);
            j.setFinishedAt(Instant.now());
        });
    }

    private BookChunkDocument toDocument(
            String userId, String bookId, TextChunk c, String embeddingVersion, String embeddingModel) {
        BookChunkDocument d = new BookChunkDocument();
        d.setUserId(userId);
        d.setBookId(bookId);
        d.setChunkIndex(c.index());
        d.setContent(c.content());
        d.setPageStart(c.pageStart());
        d.setPageEnd(c.pageEnd());
        d.setChapterTitle(c.chapterTitle());
        d.setLanguage(c.language());
        d.setTokenCount(c.tokenCount());
        d.setChunkingVersion(ChunkingService.VERSION);
        d.setEmbeddingVersion(embeddingVersion);
        d.setEmbeddingModel(embeddingModel);
        d.setSectionType(c.sectionType());
        d.setEmbedding(VectorMath.toList(embeddings.embedPassage(c.content())));
        d.setCreatedAt(Instant.now());
        return d;
    }

    private static String sample(List<PageText> pages) {
        StringBuilder sb = new StringBuilder();
        for (PageText p : pages) {
            if (sb.length() > 200_000) {
                break;
            }
            sb.append(p.text()).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------------------
    // State helpers
    // ---------------------------------------------------------------------------------------------------------

    private static final class IngestionCancelled extends RuntimeException {
        private final boolean bookGone;

        IngestionCancelled(boolean bookGone) {
            super("cancelled", null, false, false);
            this.bookGone = bookGone;
        }
    }

    private void checkCancelled(String jobId, String bookId) {
        if (!bookRepository.existsById(bookId)) {
            throw new IngestionCancelled(true);
        }
        if (cancelRequests.contains(jobId)
                || jobRepository.findById(jobId).map(IngestionJobDocument::isCancelRequested).orElse(true)) {
            throw new IngestionCancelled(false);
        }
    }

    private void setStage(String jobId, String bookId, Stage stage, int progress, String message) {
        updateJob(jobId, j -> {
            j.setStage(stage);
            j.setProgress(progress);
            j.setMessage(message);
        });
        BookDocument.Status bookStatus = switch (stage) {
            case EXTRACTING -> BookDocument.Status.EXTRACTING;
            case CHUNKING -> BookDocument.Status.CHUNKING;
            case EMBEDDING -> BookDocument.Status.EMBEDDING;
            default -> null;
        };
        if (bookStatus != null) {
            mutateBook(bookId, b -> {
                b.setStatus(bookStatus);
                b.setFailureReason(null);
            });
        }
    }

    private void fail(String jobId, String bookId, String reason) {
        updateJob(jobId, j -> {
            j.setStage(Stage.FAILED);
            j.setProgress(100);
            j.setMessage("Failed");
            j.setFailureReason(reason);
            j.setFinishedAt(Instant.now());
        });
        mutateBook(bookId, b -> {
            b.setStatus(BookDocument.Status.FAILED);
            b.setFailureReason(reason);
        });
    }

    private void finishCancelled(String jobId, String bookId, boolean bookStillExists) {
        chunkWriter.deleteByBook(bookId); // partial indexes must not be searchable
        updateJob(jobId, j -> {
            j.setStage(Stage.CANCELLED);
            j.setMessage("Cancelled");
            j.setFinishedAt(Instant.now());
        });
        if (bookStillExists) {
            mutateBook(bookId, b -> {
                b.setStatus(BookDocument.Status.FAILED);
                b.setFailureReason("Processing was cancelled");
                b.setChunkCount(0);
            });
        }
    }

    private void updateJob(String jobId, Consumer<IngestionJobDocument> change) {
        jobRepository.findById(jobId).ifPresent(j -> {
            change.accept(j);
            jobRepository.save(j);
        });
    }

    /** Read-modify-write on the freshest copy so concurrent title edits are not clobbered. */
    private void mutateBook(String bookId, Consumer<BookDocument> change) {
        bookRepository.findById(bookId).ifPresent(b -> {
            change.accept(b);
            bookRepository.save(b);
        });
    }

    private BookDocument findBook(String userId, String bookId) {
        return bookRepository
                .findByIdAndUserId(bookId, userId)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
    }

    private boolean isJobRunning(String userId, String bookId) {
        return jobRepository.findFirstByBookIdAndUserIdOrderByCreatedAtDesc(bookId, userId)
                .map(j -> j.getStage().isActive() && running.contains(j.getId()))
                .orElse(false);
    }

    private static Stage stageOf(BookDocument.Status status) {
        return switch (status) {
            case QUEUED -> Stage.QUEUED;
            case EXTRACTING -> Stage.EXTRACTING;
            case CHUNKING -> Stage.CHUNKING;
            case EMBEDDING -> Stage.EMBEDDING;
            case COMPLETED -> Stage.COMPLETED;
            case FAILED -> Stage.FAILED;
            case NEEDS_OCR -> Stage.NEEDS_OCR;
        };
    }

    // ---------------------------------------------------------------------------------------------------------
    // Files
    // ---------------------------------------------------------------------------------------------------------

    private Path uploadDir() {
        return Path.of(properties.ingestion().uploadDir()).toAbsolutePath().normalize();
    }

    /** Book ids are server-generated ObjectIds, so this path cannot be influenced by the client. */
    Path pendingFile(String bookId) {
        return uploadDir().resolve(bookId + ".pdf");
    }

    private static boolean looksLikePdf(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(1024);
            return new String(head, StandardCharsets.ISO_8859_1).contains("%PDF-");
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", path, e.getMessage());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Safety net: removes uploads that were never processed (or leaked by a crash) after 24 hours. */
    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT1H")
    void cleanStaleUploads() {
        Path dir = uploadDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(STALE_UPLOAD_AGE);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".pdf") || n.endsWith(".pdf.part");
                    })
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        log.info("Removing stale upload {}", p.getFileName());
                        deleteQuietly(p);
                    });
        } catch (IOException e) {
            log.warn("Stale upload cleanup failed: {}", e.getMessage());
        }
    }
}
