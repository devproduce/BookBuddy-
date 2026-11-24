package com.bookbuddy.memory;

import com.bookbuddy.embedding.EmbeddingProvider;
import com.bookbuddy.embedding.VectorMath;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.SourceType;
import com.bookbuddy.memory.MemoryDocument.Stability;
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

    static MemoryDocument memory(String id, Category category, String content, SourceType sourceType) {
        MemoryDocument m = new MemoryDocument();
        m.setId(id);
        m.setUserId(USER);
        m.setCategory(category);
        m.setContent(content);
        m.setSourceType(sourceType);
        m.setConfidence(sourceType == SourceType.EXPLICIT || sourceType == SourceType.USER_CONFIRMED ? 1.0 : 0.6);
        m.setImportance(0.5);
        m.setRecurrence(1);
        m.setStability(Stability.EVOLVING);
        m.setEmbedding(VectorMath.toList(FakeEmbeddings.BASE));
        m.setEmbeddingModel("fake");
        m.setStatus(Status.ACTIVE);
        m.setSourceMessageIds(new ArrayList<>());
        m.setSourceConversationIds(new ArrayList<>());
        m.setEvidence(new ArrayList<>());
        m.setCreatedAt(NOW.minusSeconds(3600));
        m.setUpdatedAt(NOW.minusSeconds(3600));
        return m;
    }

    static MemoryCandidate candidate(Category category, String content, SourceType sourceType) {
        return new MemoryCandidate(
                category,
                content,
                sourceType,
                0.9,
                0.6,
                1,
                Stability.EVOLVING,
                false,
                false,
                false,
                List.of(),
                List.of(),
                List.of());
    }

    static MemoryCandidate replacing(Category category, String content) {
        return new MemoryCandidate(
                category,
                content,
                SourceType.EXPLICIT,
                0.9,
                0.6,
                1,
                Stability.EVOLVING,
                false,
                true,
                false,
                List.of(),
                List.of(),
                List.of());
    }
}
