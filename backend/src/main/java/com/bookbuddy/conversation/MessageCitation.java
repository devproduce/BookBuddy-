package com.bookbuddy.conversation;

/**
 * A passage a reply relied on. {@code index} is the 1-based number used as {@code [index]} in the reply text, and
 * {@code excerpt} is the passage itself so the UI can show it without another round trip.
 */
public record MessageCitation(
        int index,
        String chunkId,
        String bookId,
        String bookTitle,
        Integer pageStart,
        Integer pageEnd,
        String excerpt) {}