package com.bookbuddy.document;

/** A chunk produced by {@link ChunkingService}, before embedding / persistence. */
public record TextChunk(
        int index,
        String content,
        int pageStart,
        int pageEnd,
        String language,
        int tokenCount,
        SectionType sectionType,
        String chapterTitle) {}
