package com.bookbuddy.embedding;

/**
 * Produces L2-normalised embeddings following the E5 convention: passages are prefixed with {@code "passage: "} and
 * queries with {@code "query: "}.
 */
public interface EmbeddingProvider {

    String PASSAGE_PREFIX = "passage: ";
    String QUERY_PREFIX = "query: ";

    float[] embedPassage(String text);

    float[] embedQuery(String text);

    boolean isReady();

    int dimension();

    /** Identifier stored on each chunk ({@code embeddingModel}). */
    default String modelName() {
        return getClass().getSimpleName();
    }

    /** Identifier stored on each chunk and book ({@code embeddingVersion}). */
    default String version() {
        return modelName() + "-" + dimension();
    }

    /**
     * True for real semantic models. The hash fallback is lexical only, so similarity thresholds tuned for E5 must
     * not be applied to it.
     */
    default boolean isSemantic() {
        return false;
    }
}
