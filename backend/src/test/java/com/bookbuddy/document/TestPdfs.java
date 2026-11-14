package com.bookbuddy.document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Generates small PDFs for tests. */
public final class TestPdfs {

    private TestPdfs() {}

    /** One page per entry; each entry is a list of text lines. A page with no lines is blank (like a scan). */
    public static void write(Path target, List<List<String>> pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (lines.isEmpty()) {
                    continue;
                }
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.setLeading(14);
                    cs.newLineAtOffset(50, 740);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            doc.save(target.toFile());
        }
    }
}
