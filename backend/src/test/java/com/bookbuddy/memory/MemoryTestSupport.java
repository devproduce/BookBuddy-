package com.bookbuddy.memory;

import com.bookbuddy.embedding.EmbeddingProvider;
import com.bookbuddy.embedding.VectorMath;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Classification;
import com.bookbuddy.memory.MemoryDocument.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared builders for the memory unit tests. */
final class MemoryTestSupport {

    private MemoryTestSupport() {}

    static final String USER = "u1";
    static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    /**
     * A two-dimensional "semantic" embedding model. {@link #at(double)} returns a vector whose cosine similarity to
     * {@link #BASE} is exactly the requested value, which makes merge thresholds easy to exercise.
     */
    static final class FakeEmbeddings implements EmbeddingProvider {
        static final float[] BASE = {1f, 0f};
        final Map<String, float[]> byText = new HashMap<>();
        boolean semantic = true;

        FakeEmbeddings put(String text, float[] v) {
            byText.put(text, v);
            return this;
        }

        @Override
        public float[] embedPassage(String text) {
            return byText.getOrDefault(text, BASE);
        }

        @Override
        public float[] embedQuery(String text) {
            return byText.getOrDefault(text, BASE);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public int dimension() {
            return 2;
        }

        @Override
        public String modelName() {
            return "fake";
        }

        @Override
        public boolean isSemantic() {
            return semantic;
        }

        static float[] at(double similarity) {
            return new float[] {(float) similarity, (float) Math.sqrt(1 - similarity * similarity)};
        }
    }

    static MemoryDocument memory(String id, Category category, String content, Classification classification) {
        MemoryDocument m = new MemoryDocument();
        m.setId(id);
        m.setUserId(USER);
        m.setCategory(category);
        m.setContent(content);
        m.setClassification(classification);
        m.setConfidence(classification == Classification.EXPLICIT ? 1.0 : 0.6);
        m.setImportance(0.5);
        m.setEmbedding(VectorMath.toList(FakeEmbeddings.BASE));
        m.setEmbeddingModel("fake");
        m.setStatus(Status.ACTIVE);
        m.setSourceMessageIds(new ArrayList<>());
        m.setCreatedAt(NOW.minusSeconds(3600));
        m.setUpdatedAt(NOW.minusSeconds(3600));
        return m;
    }

    static MemoryCandidate candidate(Category category, String content, Classification classification) {
        return new MemoryCandidate(category, content, classification, 0.9, 0.6, false, false, "", List.of());
    }

    static MemoryCandidate replacing(Category category, String content) {
        return new MemoryCandidate(category, content, Classification.EXPLICIT, 0.9, 0.6, false, true, "", List.of());
    }
}
