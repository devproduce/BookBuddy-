package com.bookbuddy.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bookbuddy.document.TextCleaningService.LanguageGuess;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextCleaningServiceTest {

    private final TextCleaningService service = new TextCleaningService();

    @Test
    void removesRepeatedHeadersFootersAndPageNumbers() {
        List<String> raw = new ArrayList<>();
        String[] topics = {"harbour", "orchard", "castle", "meadow", "glacier", "market", "temple", "desert"};
        for (int i = 1; i <= 8; i++) {
            raw.add("The Great Novel - Chapter Notes\n"
                    + "This is the unique body text of page number " + i + " about the " + topics[i - 1] + ".\n"
                    + "Second line of the body for the " + topics[i - 1] + " scene, page " + i + ".\n"
                    + "Page " + i + "\n");
        }
        List<PageText> pages = service.cleanPages(raw);

        assertEquals(8, pages.size());
        for (PageText p : pages) {
            assertFalse(p.text().contains("The Great Novel"), p.text());
            assertFalse(p.text().matches("(?s).*Page \\d+\\s*$"), p.text());
            assertTrue(p.text().contains("unique body text of page number " + p.pageNumber()));
        }
    }

    @Test
    void keepsLegitimateFirstLineThatDoesNotRepeat() {
        List<String> raw = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            raw.add("Unique opening sentence number " + "x".repeat(i) + " here.\nBody continues.\n" + i);
        }
        List<PageText> pages = service.cleanPages(raw);
        for (int i = 0; i < 6; i++) {
            assertTrue(pages.get(i).text().startsWith("Unique opening sentence"), pages.get(i).text());
        }
    }

    @Test
    void deHyphenatesWordsBrokenAcrossLines() {
        String cleaned = service.clean("The informa-\ntion age arrived and the Anglo-\nSaxon people stayed.");
        assertTrue(cleaned.contains("information age"), cleaned);
        assertTrue(cleaned.contains("Anglo-Saxon"), cleaned);
    }

    @Test
    void mergesHardWrappedLinesButKeepsParagraphs() {
        String cleaned = service.clean(
                "This sentence was wrapped by the typesetter\nso it continues on the next line and\nends here.\n\n"
                        + "A new paragraph begins.");
        String[] paragraphs = cleaned.split("\n\n");
        assertEquals(2, paragraphs.length);
        assertEquals(
                "This sentence was wrapped by the typesetter so it continues on the next line and ends here.",
                paragraphs[0]);
        assertEquals("A new paragraph begins.", paragraphs[1]);
    }

    @Test
    void normalizesToNfcAndStripsInvisibleCharacters() {
        String decomposed = "caf" + "e\u0301" + "\u00AD\u200B" + " au lait";
        String cleaned = service.clean(decomposed);
        assertEquals("caf\u00E9 au lait", cleaned);
    }

    @Test
    void detectsDevanagari() {
        assertTrue(service.containsDevanagari("यह हिंदी है"));
        assertFalse(service.containsDevanagari("plain english"));
        LanguageGuess hindi = service.detectLanguage("यह एक हिंदी पुस्तक का अंश है।");
        assertEquals("hi", hindi.code());
        assertEquals("en", service.detectLanguage("This is an English sentence.").code());
        assertEquals("mixed", service.detectLanguage("यह पुस्तक the best book है और बहुत अच्छी है okay").code());
    }

    @Test
    void splitsSentencesOnLatinAndDevanagariTerminators() {
        List<String> en = service.splitSentences("Hello world. How are you? Fine! Dr. Smith went home. Pi is 3.14 exactly.");
        assertEquals(
                List.of("Hello world.", "How are you?", "Fine!", "Dr. Smith went home.", "Pi is 3.14 exactly."), en);

        List<String> hi = service.splitSentences("यह पहला वाक्य है। क्या यह दूसरा है? हाँ! और यह अंतिम है।");
        assertEquals(4, hi.size());
        assertEquals("यह पहला वाक्य है।", hi.get(0));
        assertEquals("और यह अंतिम है।", hi.get(3));
    }

    @Test
    void splitsParagraphsIntoSeparateSentences() {
        List<String> s = service.splitSentences("First paragraph without a period\n\nSecond one here.");
        assertEquals(2, s.size());
    }
}
