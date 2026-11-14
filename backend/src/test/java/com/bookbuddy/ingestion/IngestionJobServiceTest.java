package com.bookbuddy.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bookbuddy.book.BookDocument;
import com.bookbuddy.book.BookRepository;
import com.bookbuddy.book.BookService;
import com.bookbuddy.common.ApiException;
import com.bookbuddy.config.BookBuddyProperties;
import com.bookbuddy.document.ChunkingService;
import com.bookbuddy.document.PdfExtractionService;
import com.bookbuddy.document.TestPdfs;
import com.bookbuddy.document.TextCleaningService;
import com.bookbuddy.embedding.HashEmbeddingProvider;
import com.bookbuddy.ingestion.IngestionDtos.BookStatusResponse;
import com.bookbuddy.vector.BookChunkDocument;
import com.bookbuddy.vector.BookChunkRepository;
import com.bookbuddy.vector.BookChunkWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

/** Drives the ingestion pipeline end to end with real PDF/cleaning/chunking/embedding and in-memory persistence. */
class IngestionJobServiceTest {

    private static final String USER = "u1";
    private static final String BOOK = "507f1f77bcf86cd799439011";

    @TempDir
    Path uploads;

    private final Map<String, BookDocument> bookStore = new HashMap<>();
    private final Map<String, IngestionJobDocument> jobStore = new HashMap<>();
    private final List<BookChunkDocument> chunkStore = new ArrayList<>();
    private final AtomicInteger jobSeq = new AtomicInteger();

    private IngestionJobService service;

    @BeforeEach
    void setUp() {
        BookRepository bookRepository = mock(BookRepository.class);
        when(bookRepository.findByIdAndUserId(anyString(), anyString())).thenAnswer(inv -> {
            BookDocument b = bookStore.get(inv.<String>getArgument(0));
            return b != null && b.getUserId().equals(inv.getArgument(1)) ? Optional.of(b) : Optional.empty();
        });
        when(bookRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(bookStore.get(inv.<String>getArgument(0))));
        when(bookRepository.existsById(anyString()))
                .thenAnswer(inv -> bookStore.containsKey(inv.<String>getArgument(0)));
        when(bookRepository.save(any(BookDocument.class))).thenAnswer(inv -> {
            BookDocument b = inv.getArgument(0);
            bookStore.put(b.getId(), b);
            return b;
        });

        IngestionJobRepository jobRepository = mock(IngestionJobRepository.class);
        when(jobRepository.save(any(IngestionJobDocument.class))).thenAnswer(inv -> {
            IngestionJobDocument j = inv.getArgument(0);
            if (j.getId() == null) {
                j.setId("job-" + jobSeq.incrementAndGet());
            }
            jobStore.put(j.getId(), j);
            return j;
        });
        when(jobRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(jobStore.get(inv.<String>getArgument(0))));
        when(jobRepository.findFirstByBookIdAndUserIdOrderByCreatedAtDesc(anyString(), anyString()))
                .thenAnswer(inv -> jobStore.values().stream()
                        .filter(j -> j.getBookId().equals(inv.getArgument(0)))
                        .reduce((a, b) -> b));

        BookChunkWriter writer = mock(BookChunkWriter.class);
        doAnswer(inv -> {
                    for (BookChunkDocument c : inv.<List<BookChunkDocument>>getArgument(0)) {
                        chunkStore.removeIf(e -> e.getBookId().equals(c.getBookId()) && e.getChunkIndex() == c.getChunkIndex());
                        chunkStore.add(c);
                    }
                    return null;
                })
                .when(writer)
                .upsertAll(any());
        when(writer.deleteFromIndex(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            int from = inv.getArgument(1);
            chunkStore.removeIf(c -> c.getBookId().equals(inv.getArgument(0)) && c.getChunkIndex() >= from);
            return 0L;
        });
        when(writer.deleteByBook(anyString())).thenAnswer(inv -> {
            chunkStore.removeIf(c -> c.getBookId().equals(inv.getArgument(0)));
            return 0L;
        });

        BookBuddyProperties props = new BookBuddyProperties(
                null, null, null, null,
                new BookBuddyProperties.Ingestion(uploads.toString(), 800, 40, 100, 20),
                null, null, null, null);
        TextCleaningService cleaning = new TextCleaningService();
        service = new IngestionJobService(
                bookRepository,
                jobRepository,
                mock(BookChunkRepository.class),
                writer,
                mock(BookService.class),
                new PdfExtractionService(),
                cleaning,
                new ChunkingService(cleaning, 100, 20),
                new HashEmbeddingProvider(),
                props,
                Runnable::run);
    }

    private static List<String> bodyLines(int page) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lines.add("Page " + page + " sentence " + i + " describes the lighthouse keeper and the sea.");
        }
        return lines;
    }

    private BookDocument registerBook(byte[] pdfBytes) throws Exception {
        BookDocument b = new BookDocument();
        b.setId(BOOK);
        b.setUserId(USER);
        b.setTitle("Test");
        b.setContentHash(HexFormatHelper.sha256(pdfBytes));
        b.setFileSizeBytes((long) pdfBytes.length);
        bookStore.put(BOOK, b);
        return b;
    }

    private byte[] textPdf(int pages) throws IOException {
        Path tmp = Files.createTempFile("bb", ".pdf");
        try {
            List<List<String>> content = new ArrayList<>();
            for (int p = 1; p <= pages; p++) {
                content.add(bodyLines(p));
            }
            TestPdfs.write(tmp, content);
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void uploadProcessCompletesAndDeletesThePdf() throws Exception {
        byte[] pdf = textPdf(4);
        registerBook(pdf);

        BookStatusResponse afterUpload =
                service.upload(USER, BOOK, new MockMultipartFile("file", "b.pdf", "application/pdf", pdf));
        assertTrue(afterUpload.fileUploaded());
        assertTrue(Files.exists(uploads.resolve(BOOK + ".pdf")));

        BookStatusResponse started = service.start(USER, BOOK);

        BookDocument book = bookStore.get(BOOK);
        assertEquals(BookDocument.Status.COMPLETED, book.getStatus(), book.getFailureReason());
        assertEquals(4, book.getPageCount());
        assertEquals("en", book.getLanguage());
        assertEquals(ChunkingService.VERSION, book.getChunkingVersion());
        assertEquals("hash-v1-384", book.getEmbeddingVersion());
        assertTrue(book.getChunkCount() > 0);
        assertEquals(book.getChunkCount(), chunkStore.size());
        assertFalse(Files.exists(uploads.resolve(BOOK + ".pdf")), "PDF must be deleted after processing");

        BookChunkDocument first = chunkStore.get(0);
        assertEquals(USER, first.getUserId());
        assertEquals(384, first.getEmbedding().size());
        assertEquals("hash-sha256-fallback", first.getEmbeddingModel());
        assertNotNull(first.getPageStart());

        BookStatusResponse status = service.status(USER, BOOK);
        assertEquals(IngestionJobDocument.Stage.COMPLETED, status.stage());
        assertEquals(100, status.progress());
        assertFalse(status.fileUploaded());
        assertNotNull(started.jobId());
    }

    @Test
    void reprocessingIsIdempotentAndDropsStaleChunks() throws Exception {
        byte[] pdf = textPdf(4);
        registerBook(pdf);
        // leftover chunk from an earlier, longer run
        BookChunkDocument stale = new BookChunkDocument();
        stale.setBookId(BOOK);
        stale.setUserId(USER);
        stale.setChunkIndex(999);
        stale.setContent("stale");
        chunkStore.add(stale);

        for (int i = 0; i < 2; i++) {
            service.upload(USER, BOOK, new MockMultipartFile("file", "b.pdf", "application/pdf", pdf));
            service.start(USER, BOOK);
        }
        assertEquals(bookStore.get(BOOK).getChunkCount(), chunkStore.size());
        assertTrue(chunkStore.stream().noneMatch(c -> c.getChunkIndex() == 999));
        assertEquals(2, jobStore.size());
    }

    @Test
    void scannedPdfNeedsOcrAndFileIsStillDeleted() throws Exception {
        Path tmp = Files.createTempFile("bb", ".pdf");
        TestPdfs.write(tmp, List.of(List.of(), List.of(), List.of()));
        byte[] pdf = Files.readAllBytes(tmp);
        Files.delete(tmp);
        registerBook(pdf);

        service.upload(USER, BOOK, new MockMultipartFile("file", "scan.pdf", "application/pdf", pdf));
        service.start(USER, BOOK);

        assertEquals(BookDocument.Status.NEEDS_OCR, bookStore.get(BOOK).getStatus());
        assertEquals(IngestionJobDocument.Stage.NEEDS_OCR, service.status(USER, BOOK).stage());
        assertTrue(chunkStore.isEmpty());
        assertFalse(Files.exists(uploads.resolve(BOOK + ".pdf")));
    }

    @Test
    void corruptPdfFailsAndFileIsDeleted() throws Exception {
        byte[] bogus = "%PDF-1.4 this is not really a pdf".getBytes();
        registerBook(bogus);
        service.upload(USER, BOOK, new MockMultipartFile("file", "x.pdf", "application/pdf", bogus));
        service.start(USER, BOOK);

        assertEquals(BookDocument.Status.FAILED, bookStore.get(BOOK).getStatus());
        assertNotNull(bookStore.get(BOOK).getFailureReason());
        assertFalse(Files.exists(uploads.resolve(BOOK + ".pdf")));
    }

    @Test
    void uploadRejectsNonPdfAndHashMismatch() throws Exception {
        byte[] pdf = textPdf(1);
        registerBook(pdf);

        ApiException notPdf = assertThrows(ApiException.class, () -> service.upload(
                USER, BOOK, new MockMultipartFile("file", "a.txt", "text/plain", "hello world".getBytes())));
        assertEquals("NOT_A_PDF", notPdf.getCode());

        byte[] other = "%PDF-1.4 different".getBytes();
        ApiException mismatch = assertThrows(ApiException.class, () -> service.upload(
                USER, BOOK, new MockMultipartFile("file", "a.pdf", "application/pdf", other)));
        assertEquals("FILE_HASH_MISMATCH", mismatch.getCode());
        assertFalse(Files.exists(uploads.resolve(BOOK + ".pdf")));
        assertFalse(Files.exists(uploads.resolve(BOOK + ".pdf.part")));
    }

    @Test
    void processWithoutUploadIsConflictAndOtherUsersCannotAccess() throws Exception {
        registerBook(textPdf(1));

        ApiException noFile = assertThrows(ApiException.class, () -> service.start(USER, BOOK));
        assertEquals(HttpStatus.CONFLICT, noFile.getStatus());
        assertEquals("FILE_REQUIRED", noFile.getCode());

        ApiException foreign = assertThrows(ApiException.class, () -> service.status("someone-else", BOOK));
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatus());
    }

    @Test
    void cancelOfStaleActiveJobFinishesCancelledAndRemovesChunks() throws Exception {
        byte[] pdf = textPdf(1);
        registerBook(pdf);
        IngestionJobDocument job = new IngestionJobDocument();
        job.setId("job-x");
        job.setUserId(USER);
        job.setBookId(BOOK);
        job.setStage(IngestionJobDocument.Stage.EMBEDDING);
        jobStore.put(job.getId(), job);
        BookChunkDocument partial = new BookChunkDocument();
        partial.setBookId(BOOK);
        partial.setChunkIndex(0);
        chunkStore.add(partial);

        service.cancel(USER, BOOK);

        assertEquals(IngestionJobDocument.Stage.CANCELLED, jobStore.get("job-x").getStage());
        assertTrue(chunkStore.isEmpty());
        assertEquals(BookDocument.Status.FAILED, bookStore.get(BOOK).getStatus());
    }

    /** Tiny helper so the test does not depend on production hashing code. */
    private static final class HexFormatHelper {
        static String sha256(byte[] bytes) throws Exception {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        }
    }
}
