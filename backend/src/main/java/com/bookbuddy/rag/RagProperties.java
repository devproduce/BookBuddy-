package com.bookbuddy.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code bookbuddy.rag.*}. Non-positive numbers and blank modes fall back to the defaults, so
 * {@link #defaults()} is always usable.
 *
 * @param expandTruncatedNeighbors when a retrieved chunk starts or ends mid-sentence, borrow the missing part from
 *                                 the neighbouring chunk of the same book
 * @param neighborMaxChars         upper bound on the characters borrowed from one neighbour
 * @param rewriteMode              follow-up query rewriting before retrieval: {@code heuristic} (default, no LLM
 *                                 call), {@code llm} (one cheap call, heuristic on failure) or {@code off}
 */
@ConfigurationProperties("bookbuddy.rag")
public record RagProperties(boolean expandTruncatedNeighbors, int neighborMaxChars, String rewriteMode) {

    public static final String MODE_HEURISTIC = "heuristic";
    public static final String MODE_LLM = "llm";
    public static final String MODE_OFF = "off";

    public RagProperties {
        neighborMaxChars = neighborMaxChars > 0 ? neighborMaxChars : 400;
        String mode = rewriteMode == null ? "" : rewriteMode.strip().toLowerCase(java.util.Locale.ROOT);
        rewriteMode = MODE_LLM.equals(mode) || MODE_OFF.equals(mode) ? mode : MODE_HEURISTIC;
    }

    public static RagProperties defaults() {
        return new RagProperties(true, 0, null);
    }
}
