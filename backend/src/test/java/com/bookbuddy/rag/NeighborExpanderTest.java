package com.bookbuddy.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bookbuddy.document.SectionType;
import com.bookbuddy.vector.BookChunkDocument;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NeighborExpanderTest {

    private final Map<Integer, BookChunkDocument> book = new HashMap<>();

    private BookChunkDocument chunk(int index, String text, int pageStart, int pageEnd) {
        BookChunkDocument c = new BookChunkDocument();
        c.setId("c" + index);
        c.setBookId("b1");
        c.setUserId("u1");
        c.setChunkIndex(index);
        c.setContent(text);
        c.setPageStart(pageStart);
        c.setPageEnd(pageEnd);
        c.setSectionType(SectionType.BODY);
        book.put(index, c);
        return c;
    }

    private BookChunkDocument expand(BookChunkDocument c, int max) {
        return NeighborExpander.expand(c, (bookId, i) -> Optional.ofNullable(book.get(i)), max);
    }

    @Test
    void detectsTruncatedEndsAndStarts() {
        assertTrue(NeighborExpander.endsTruncated("The keeper walked to the"));
        assertFalse(NeighborExpander.endsTruncated("The keeper walked home."));
        assertFalse(NeighborExpander.endsTruncated("He said, \"Go home!\" "));
        assertFalse(NeighborExpander.endsTruncated("\u092F\u0939 \u0939\u0948\u0964"));
        assertTrue(NeighborExpander.startsTruncated("and then the storm came."));
        assertTrue(NeighborExpander.startsTruncated(", said the keeper."));
        assertFalse(NeighborExpander.startsTruncated("The storm came."));
        assertFalse(NeighborExpander.startsTruncated("\u092F\u0939 \u0939\u0948"));
    }

    @Test
    void completeChunksAreLeftAlone() {
        BookChunkDocument c = chunk(1, "A whole sentence. Another whole sentence.", 3, 3);
        chunk(2, "Next chunk.", 3, 4);
        assertSame(c, expand(c, 400));
    }

    @Test
    void truncatedEndIsCompletedFromTheNextChunkWithoutRepeatingTheOverlap() {
        BookChunkDocument c = chunk(1,
                "The keeper lived alone for forty years. Every night he climbed the stairs to light the lamp and watch the",
                10, 10);
        chunk(2,
                "to light the lamp and watch the ships pass in the dark. Gulls circled the tower at dawn.",
                10, 11);

        BookChunkDocument e = expand(c, 400);

        assertNotSame(c, e);
        assertEquals(
                "The keeper lived alone for forty years. Every night he climbed the stairs to light the lamp and "
                        + "watch the ships pass in the dark.",
                e.getContent());
        assertEquals(10, e.getPageStart());
        assertEquals(11, e.getPageEnd());
        assertEquals("c1", e.getId());
        assertEquals("The keeper lived alone for forty years. Every night he climbed the stairs to light the lamp"
                + " and watch the", c.getContent(), "the stored chunk is never mutated");
    }

    @Test
    void truncatedStartIsCompletedFromThePreviousChunk() {
        chunk(0, "It rained all week. The old keeper said that the sea would always remember", 4, 4);
        BookChunkDocument c = chunk(1, "the sea would always remember everyone who had loved it. Nobody argued.", 4, 5);
        // chunk 1 starts mid-sentence (lower case); its overlap with chunk 0 is "the sea would always remember"

        BookChunkDocument e = expand(c, 400);

        assertEquals(
                "The old keeper said that the sea would always remember everyone who had loved it. Nobody argued.",
                e.getContent());
        assertEquals(4, e.getPageStart());
        assertEquals(5, e.getPageEnd());
    }

    @Test
    void borrowedTextIsCappedAtMaxChars() {
        BookChunkDocument c = chunk(1, "He kept walking and walking and walking", 1, 1);
        chunk(2, "no stop in sight " + "and on and on ".repeat(50) + "until the end.", 1, 2);

        BookChunkDocument e = expand(c, 60);

        int added = e.getContent().length() - c.getContent().length();
        assertTrue(added > 0 && added <= 61, "added " + added);
    }

    @Test
    void missingOrForeignNeighboursChangeNothing() {
        BookChunkDocument c = chunk(1, "The keeper walked to the", 1, 1);
        assertSame(c, expand(c, 400), "no next chunk");

        BookChunkDocument front = chunk(2, "Front matter continues here.", 1, 1);
        front.setSectionType(SectionType.FRONT_MATTER);
        assertSame(c, expand(c, 400), "neighbour from another section is ignored");
    }
}
