package com.bookbuddy.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bookbuddy.memory.MemoryControlDetector.Kind;
import org.junit.jupiter.api.Test;

class MemoryControlDetectorTest {

    private final MemoryControlDetector detector = new MemoryControlDetector();

    @Test
    void detectsForgetRequests() {
        var d = detector.detect("Please forget that I like horror");
        assertEquals(Kind.FORGET, d.kind());
        assertTrue(d.isControl());
        assertTrue(d.topic().toLowerCase().contains("horror") || d.topic().toLowerCase().contains("like"));
    }

    @Test
    void detectsCorrections() {
        var d = detector.detect("That's wrong — I meant I prefer paperbacks");
        assertEquals(Kind.CORRECT, d.kind());
    }

    @Test
    void ignoresOrdinaryChat() {
        assertFalse(detector.detect("What does the book say about courage?").isControl());
        assertEquals(Kind.NONE, detector.detect("").kind());
    }
}
