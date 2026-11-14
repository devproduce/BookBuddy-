package com.bookbuddy.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bookbuddy.document.TextCleaningService;
import com.bookbuddy.rag.QuoteVerifier.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteVerifierTest {

    private final QuoteVerifier verifier = new QuoteVerifier(new TextCleaningService());

    private static final List<String> SOURCES = List.of(
            "The old lighthouse keeper said that the sea never forgets\nanyone who loves it. He lived alone for forty years.",
            "In the village market, bread was cheap and gossip was cheaper.");

    @Test
    void keepsVerbatimQuotesEvenWithDifferentWhitespace() {
        String answer = "The keeper said \"the sea never forgets anyone who loves it\" [1].";
        Result r = verifier.verify(answer, SOURCES);
        assertEquals(answer, r.text());
        assertEquals(1, r.verified());
        assertEquals(0, r.stripped());
    }

    @Test
    void keepsCurlyQuotesAndEllipsisFragments() {
        String answer = "He said \u201Cthe sea never forgets ... forty years\u201D.";
        Result r = verifier.verify(answer, SOURCES);
        assertEquals(answer, r.text());
        assertEquals(1, r.verified());
    }

    @Test
    void stripsInventedQuotes() {
        Result r = verifier.verify("The keeper declared \"the ocean is a cruel and fickle mistress of fate\" loudly.", SOURCES);
        assertEquals("The keeper declared the ocean is a cruel and fickle mistress of fate loudly.", r.text());
        assertEquals(1, r.stripped());
        assertFalse(r.text().contains("\""));
    }

    @Test
    void fixesNearMissQuotesWithTheSourceSentence() {
        Result r = verifier.verify(
                "He said \"the lighthouse keeper said the sea never forgets anyone who loves it dearly\".", SOURCES);
        assertEquals(1, r.fixed());
        assertTrue(r.text().contains("\"The old lighthouse keeper said that the sea never forgets anyone who loves it.\""),
                r.text());
    }

    @Test
    void ignoresVeryShortScareQuotes() {
        String answer = "It is a \"fun\" read.";
        assertEquals(answer, verifier.verify(answer, SOURCES).text());
    }

    @Test
    void mixedVerifiedAndInvented() {
        Result r = verifier.verify(
                "\"bread was cheap and gossip was cheaper\" but also \"dragons ruled the northern valleys\".", SOURCES);
        assertEquals(1, r.verified());
        assertEquals(1, r.stripped());
        assertTrue(r.text().startsWith("\"bread was cheap and gossip was cheaper\""));
        assertTrue(r.text().endsWith("dragons ruled the northern valleys."));
    }

    @Test
    void handlesEmptyAnswer() {
        assertEquals("", verifier.verify("", SOURCES).text());
        assertEquals("", verifier.verify(null, SOURCES).text());
    }
}
