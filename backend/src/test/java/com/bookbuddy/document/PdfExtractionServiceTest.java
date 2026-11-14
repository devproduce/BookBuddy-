package com.bookbuddy.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bookbuddy.document.PdfExtractionService.ExtractionResult;
import com.bookbuddy.document.PdfExtractionService.PdfExtractionException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfExtractionServiceTest {

    private final PdfExtractionService service = new PdfExtractionService();

    @TempDir
    Path tmp;

    @Test
    void extractsPerPageTextFromPathAndStreamWithoutDeletingTheFile() throws Exception {
        Path pdf = tmp.resolve("book.pdf");
        TestPdfs.write(pdf, List.of(
                List.of("Hello first page.", "It has two lines."),
                List.of("Second page text here.")));

        ExtractionResult result = service.extract(pdf);
        assertEquals(2, result.pageCount());
        assertFalse(result.encrypted());
        assertTrue(result.pages().get(0).text().contains("Hello first page."));
        assertTrue(result.pages().get(1).text().contains("Second page text here."));
        assertEquals(1, result.pages().get(0).pageNumber());
        assertTrue(result.totalChars() > 30);
        assertTrue(result.avgCharsPerPage() > 15);
        assertTrue(Files.exists(pdf), "caller owns the file");

        ExtractionResult fromStream = service.extract(new ByteArrayInputStream(Files.readAllBytes(pdf)));
        assertEquals(2, fromStream.pageCount());
        assertEquals(result.totalChars(), fromStream.totalChars());
    }

    @Test
    void blankPagesYieldLowCharCounts() throws Exception {
        Path pdf = tmp.resolve("scan.pdf");
        TestPdfs.write(pdf, List.of(List.of(), List.of(), List.of()));
        ExtractionResult result = service.extract(pdf);
        assertEquals(3, result.pageCount());
        assertEquals(0, result.totalChars());
        assertEquals(0.0, result.avgCharsPerPage());
        assertEquals(0.0, result.fractionOfPagesWithText(10));
    }

    @Test
    void enforcesPageLimit() throws Exception {
        Path pdf = tmp.resolve("long.pdf");
        TestPdfs.write(pdf, List.of(List.of("a b c"), List.of("d e f"), List.of("g h i")));
        PdfExtractionException ex = assertThrows(PdfExtractionException.class, () -> service.extract(pdf, 2));
        assertTrue(ex.isTooManyPages());
    }

    @Test
    void rejectsGarbage() throws Exception {
        Path bad = tmp.resolve("bad.pdf");
        Files.write(bad, "definitely not a pdf".getBytes(StandardCharsets.UTF_8));
        PdfExtractionException ex = assertThrows(PdfExtractionException.class, () -> service.extract(bad));
        assertFalse(ex.isEncrypted());
    }
}
