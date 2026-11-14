package com.bookbuddy.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

/**
 * Extracts per-page text with PDFBox 3.x. This service never deletes anything: the caller owns the file / stream
 * lifecycle.
 */
@Service
public class PdfExtractionService {

    public static final int UNLIMITED_PAGES = Integer.MAX_VALUE;

    public record ExtractionResult(
            List<PageText> pages, int pageCount, long totalChars, double avgCharsPerPage, boolean encrypted) {

        /** Fraction of pages whose text length is at least {@code minChars}. */
        public double fractionOfPagesWithText(int minChars) {
            if (pages.isEmpty()) {
                return 0;
            }
            long good = pages.stream().filter(p -> p.text().strip().length() >= minChars).count();
            return (double) good / pages.size();
        }
    }

    /** Thrown for unreadable, encrypted or oversized PDFs. {@link #isEncrypted()} flags password protection. */
    public static class PdfExtractionException extends RuntimeException {
        private final boolean encrypted;
        private final boolean tooManyPages;

        public PdfExtractionException(String message, boolean encrypted, boolean tooManyPages, Throwable cause) {
            super(message, cause);
            this.encrypted = encrypted;
            this.tooManyPages = tooManyPages;
        }

        public boolean isEncrypted() {
            return encrypted;
        }

        public boolean isTooManyPages() {
            return tooManyPages;
        }
    }

    public ExtractionResult extract(Path pdf) {
        return extract(pdf, UNLIMITED_PAGES);
    }

    public ExtractionResult extract(Path pdf, int maxPages) {
        try (RandomAccessRead source = new RandomAccessReadBufferedFile(pdf)) {
            return extractFrom(source, maxPages);
        } catch (IOException e) {
            throw new PdfExtractionException("Could not read PDF: " + e.getMessage(), false, false, e);
        }
    }

    public ExtractionResult extract(InputStream in) {
        return extract(in, UNLIMITED_PAGES);
    }

    /** Reads the stream fully (it is not closed). */
    public ExtractionResult extract(InputStream in, int maxPages) {
        try (RandomAccessRead source = new RandomAccessReadBuffer(in)) {
            return extractFrom(source, maxPages);
        } catch (IOException e) {
            throw new PdfExtractionException("Could not read PDF: " + e.getMessage(), false, false, e);
        }
    }

    private ExtractionResult extractFrom(RandomAccessRead source, int maxPages) {
        try (PDDocument doc = Loader.loadPDF(source)) {
            boolean encrypted = doc.isEncrypted();
            if (encrypted && !doc.getCurrentAccessPermission().canExtractContent()) {
                throw new PdfExtractionException("PDF is encrypted and text extraction is not permitted", true, false, null);
            }
            int pageCount = doc.getNumberOfPages();
            if (pageCount > maxPages) {
                throw new PdfExtractionException(
                        "PDF has " + pageCount + " pages; the limit is " + maxPages, false, true, null);
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<PageText> pages = new ArrayList<>(pageCount);
            long total = 0;
            for (int p = 1; p <= pageCount; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                text = text == null ? "" : text;
                total += text.strip().length();
                pages.add(PageText.raw(p, text));
            }
            double avg = pageCount == 0 ? 0 : (double) total / pageCount;
            return new ExtractionResult(pages, pageCount, total, avg, encrypted);
        } catch (InvalidPasswordException e) {
            throw new PdfExtractionException("PDF is password protected", true, false, e);
        } catch (IOException e) {
            throw new PdfExtractionException("Could not read PDF: " + e.getMessage(), false, false, e);
        }
    }
}
