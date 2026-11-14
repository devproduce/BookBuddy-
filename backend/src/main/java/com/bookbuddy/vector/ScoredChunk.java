package com.bookbuddy.vector;

/** A chunk plus its relevance score (cosine similarity for vector hits, keyword score or RRF score otherwise). */
public record ScoredChunk(BookChunkDocument chunk, double score) {}
