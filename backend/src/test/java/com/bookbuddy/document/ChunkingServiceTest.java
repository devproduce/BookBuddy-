package com.bookbuddy.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkingServiceTest {

    private final TextCleaningService cleaning = new TextCleaningService();
    private final ChunkingService chunker = new ChunkingService(cleaning, 100, 20);

    private static PageText page(int n, String text) {
        return new PageText(n, text, text);
    }

    private static String sentences(int from, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + count; i++) {
            sb.append("This is numbered sentence ").append(i).append(" of the story. ");
        }
        return sb.toString().strip();
    }

    @Test
    void chunksRespectBudgetNeverCutSentencesAndOverlap() {
        List<PageText> pages = List.of(page(1, sentences(0, 30)), page(2, sentences(30, 30)));
        List<TextChunk> chunks = chunker.chunk(pages);

        assertTrue(chunks.size() > 3, "expected several chunks, got " + chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk c = chunks.get(i);
            assertEquals(i, c.index());
            assertTrue(c.tokenCount() <= 100, "chunk " + i + " has " + c.tokenCount() + " tokens");
            assertTrue(c.content().endsWith("story."), "chunk should end on a sentence boundary: " + c.content());
            assertTrue(c.content().startsWith("This is numbered sentence"), c.content());
            assertEquals("en", c.language());
            assertEquals(SectionType.BODY, c.sectionType());
        }
        // overlap: the last sentence of chunk i reappears at the start of chunk i+1
        for (int i = 0; i + 1 < chunks.size(); i++) {
            List<String> a = cleaning.splitSentences(chunks.get(i).content());
            List<String> b = cleaning.splitSentences(chunks.get(i + 1).content());
            assertTrue(b.contains(a.get(a.size() - 1)), "no overlap between chunk " + i + " and " + (i + 1));
        }
        // every sentence is covered
        String all = String.join(" ", chunks.stream().map(TextChunk::content).toList());
        for (int i = 0; i < 60; i++) {
            assertTrue(all.contains("numbered sentence " + i + " of"), "missing sentence " + i);
        }
    }

    @Test
    void attachesPageRanges() {
        List<TextChunk> chunks = chunker.chunk(List.of(page(5, sentences(0, 30)), page(6, sentences(30, 30))));
        assertEquals(5, chunks.get(0).pageStart());
        assertEquals(6, chunks.get(chunks.size() - 1).pageEnd());
        assertTrue(chunks.stream().anyMatch(c -> c.pageStart() == 5 && c.pageEnd() == 6), "a chunk spans the break");
        for (TextChunk c : chunks) {
            assertTrue(c.pageStart() <= c.pageEnd());
        }
    }

    @Test
    void sentenceRunningAcrossPageBreakStaysTogether() {
        List<TextChunk> chunks = chunker.chunk(List.of(
                page(1, "The hero walked across the"), page(2, "river and never looked back. The end.")));
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).content().contains("across the river and never looked back."));
        assertEquals(1, chunks.get(0).pageStart());
        assertEquals(2, chunks.get(0).pageEnd());
    }

    @Test
    void oversizedSentenceIsSplitOnWordsAndTerminates() {
        String giant = ("word ".repeat(400)).strip() + ".";
        List<TextChunk> chunks = chunker.chunk(List.of(page(1, giant)));
        assertTrue(chunks.size() >= 4);
        chunks.forEach(c -> assertTrue(c.tokenCount() <= 100));
    }

    @Test
    void marksFrontAndBackMatter() {
        List<PageText> pages = new ArrayList<>();
        pages.add(page(1, "All rights reserved. ISBN 978-0-00-000000-0. Printed in Canada."));
        pages.add(page(2, "Contents\nChapter One ........ 1\nChapter Two ........ 12\nChapter Three ........ 25\n"
                + "Chapter Four ........ 40\nChapter Five ........ 55\nChapter Six ........ 70\nEpilogue ........ 90"));
        for (int i = 3; i <= 20; i++) {
            pages.add(page(i, sentences(i * 10, 4)));
        }
        pages.add(page(21, "Index\napple, 12\nbanana, 14, 18\ncherry, 20\ndate, 22-25\nelder, 30\nfig, 31\ngrape, 40"));
        pages.add(page(22, "honey, 41\nivy, 42\njam, 43\nkiwi, 44\nlemon, 45\nmango, 46\nnut, 47"));

        List<SectionType> types = chunker.classifyPages(pages);
        assertEquals(SectionType.FRONT_MATTER, types.get(0));
        assertEquals(SectionType.FRONT_MATTER, types.get(1));
        assertEquals(SectionType.BODY, types.get(10));
        assertEquals(SectionType.BACK_MATTER, types.get(20));
        assertEquals(SectionType.BACK_MATTER, types.get(21));

        List<TextChunk> chunks = chunker.chunk(pages);
        assertTrue(chunks.stream().anyMatch(c -> c.sectionType() == SectionType.FRONT_MATTER));
        assertTrue(chunks.stream().anyMatch(c -> c.sectionType() == SectionType.BODY));
        assertTrue(chunks.stream().anyMatch(c -> c.sectionType() == SectionType.BACK_MATTER));
        // chunks never mix section types
        for (TextChunk c : chunks) {
            if (c.sectionType() == SectionType.BODY) {
                assertFalse(c.content().contains("Index"), c.content());
            }
        }
    }

    @Test
    void detectsChapterTitlesAndDevanagariLanguage() {
        String hindi = "यह एक बहुत लंबा वाक्य है जो कहानी का हिस्सा है। ".repeat(30).strip();
        List<PageText> pages = List.of(
                new PageText(1, hindi, "अध्याय 1 आरंभ\n" + hindi),
                new PageText(2, hindi, hindi));
        List<TextChunk> chunks = chunker.chunk(pages);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> "hi".equals(c.language())));
        assertEquals("अध्याय 1 आरंभ", chunks.get(0).chapterTitle());
        assertTrue(chunks.stream().allMatch(c -> c.tokenCount() <= 100));
    }

    @Test
    void emptyInputGivesNoChunks() {
        assertTrue(chunker.chunk(List.of()).isEmpty());
        assertTrue(chunker.chunk(List.of(page(1, "   "))).isEmpty());
    }

    @Test
    void estimatesTokensAsFourCharsPerToken() {
        assertEquals(0, chunker.estimateTokens(""));
        assertEquals(3, chunker.estimateTokens("abcdefghij")); // 10 chars -> ceil(2.5)
        assertEquals(1, chunker.estimateTokens("a"));
    }
}
