package com.bookbuddy.memory;

import static com.bookbuddy.memory.MemoryTestSupport.NOW;
import static com.bookbuddy.memory.MemoryTestSupport.USER;
import static com.bookbuddy.memory.MemoryTestSupport.memory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookbuddy.conversation.TokenEstimator;
import com.bookbuddy.embedding.VectorMath;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Classification;
import com.bookbuddy.memory.MemoryDocument.Sensitivity;
import com.bookbuddy.memory.MemoryDocument.Status;
import com.bookbuddy.memory.MemoryTestSupport.FakeEmbeddings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/** Retrieval budget: core profile ~150 tokens, ranked memories within a hard token and count budget. */
class MemoryRetrievalServiceTest {

    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final FakeEmbeddings embeddings = new FakeEmbeddings();

    private MemoryRetrievalService service(MemoryProperties props) {
        return new MemoryRetrievalService(repository, embeddings, props, mongo);
    }

    private MemoryRetrievalService service() {
        return service(MemoryProperties.defaults());
    }

    /** A memory whose cosine similarity to the (default) query vector is {@code similarity}. */
    private static MemoryDocument at(String id, Category category, String content, Classification c, double similarity) {
        MemoryDocument m = memory(id, category, content, c);
        m.setEmbedding(VectorMath.toList(FakeEmbeddings.at(similarity)));
        return m;
    }

    private static final String SIXTY = "word ".repeat(12).strip() + " "; // 60 characters

    private static List<String> ids(MemoryContext c) {
        return c.memoryIds();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Core profile
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void coreProfileStaysWithinItsTokenBudget() {
        List<MemoryDocument> usable = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            usable.add(at("m" + i, Category.ABOUT_ME, "Fact number " + i + " about the reader: " + "detail ".repeat(10), Classification.EXPLICIT, 0.5));
        }

        MemoryRetrievalService.CoreProfile core = service().buildCore(usable, 150);

        assertTrue(core.estimatedTokens() <= 150, "was " + core.estimatedTokens());
        assertFalse(core.items().isEmpty());
        assertTrue(core.items().size() < 30);
    }

    @Test
    void coreProfileHoldsOnlyStatedNonSensitiveMemories() {
        MemoryDocument stated = at("a", Category.ABOUT_ME, "Is a teacher", Classification.EXPLICIT, 0.5);
        MemoryDocument verifiedGuess = at("b", Category.INTERESTS, "Likes history", Classification.INFERRED, 0.5);
        verifiedGuess.setUserVerified(true);
        MemoryDocument guess = at("c", Category.INTERESTS, "Probably likes sci-fi", Classification.INFERRED, 0.5);
        MemoryDocument sensitive = at("d", Category.ABOUT_ME, "Has ADHD", Classification.EXPLICIT, 0.5);
        sensitive.setSensitivity(Sensitivity.SENSITIVE);
        MemoryDocument mood = at("e", Category.EMOTIONAL_CONTEXT, "Feeling low", Classification.EXPLICIT, 0.5);
        MemoryDocument experience = at("f", Category.EXPERIENCES, "Visited Jaipur", Classification.EXPLICIT, 0.5);

        List<String> core = service().buildCore(List.of(stated, verifiedGuess, guess, sensitive, mood, experience), 150)
                .items().stream().map(MemoryDocument::getId).toList();

        assertEquals(List.of("a", "b"), core);
    }

    @Test
    void pinnedStatedMemoriesComeFirstEvenOutsideTheCoreCategories() {
        MemoryDocument about = at("a", Category.ABOUT_ME, "Is a teacher", Classification.EXPLICIT, 0.5);
        MemoryDocument pinned = at("p", Category.EXPERIENCES, "Lived in Pune for ten years", Classification.EXPLICIT, 0.5);
        pinned.setPinned(true);

        List<String> core = service().buildCore(List.of(about, pinned), 150).items().stream()
                .map(MemoryDocument::getId).toList();

        assertEquals(List.of("p", "a"), core);
    }

    @Test
    void coreProfileIsAlwaysIncludedEvenWhenNothingMatchesTheMessage() {
        MemoryDocument about = at("a", Category.ABOUT_ME, "Is a teacher", Classification.EXPLICIT, 0.0);

        MemoryContext ctx = service().select(List.of(about), FakeEmbeddings.BASE, NOW);

        assertEquals(List.of("a"), ids(ctx));
        assertTrue(ctx.promptBlock().contains("Core profile"));
        assertTrue(ctx.promptBlock().contains("- Is a teacher"));
        assertFalse(ctx.promptBlock().contains("Relevant to this message"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Ranking and budgets
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void rankedBySimilarityTimesImportanceTimesRecency() {
        MemoryDocument highSimilarity = at("sim", Category.EXPERIENCES, "A", Classification.EXPLICIT, 0.9);
        highSimilarity.setImportance(0.5);
        MemoryDocument highImportance = at("imp", Category.EXPERIENCES, "B", Classification.EXPLICIT, 0.6);
        highImportance.setImportance(1.0);
        MemoryDocument stale = at("old", Category.EXPERIENCES, "C", Classification.EXPLICIT, 0.9);
        stale.setImportance(0.5);
        stale.setUpdatedAt(NOW.minus(Duration.ofDays(200)));
        highSimilarity.setUpdatedAt(NOW);
        highImportance.setUpdatedAt(NOW);

        // scores: sim 0.9*0.5*1 = 0.45, imp 0.6*1.0*1 = 0.60, old 0.9*0.5*0.2 = 0.09
        MemoryContext ctx = service().select(List.of(stale, highSimilarity, highImportance), FakeEmbeddings.BASE, NOW);

        assertEquals(List.of("imp", "sim", "old"), ids(ctx));
    }

    @Test
    void rankedMemoriesRespectTheTokenBudget() {
        MemoryProperties props = new MemoryProperties(0, 0, 0, 0, 40, 0, 0, 0, 0, 0, 0, 0, 0);
        List<MemoryDocument> usable = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            MemoryDocument m = at("m" + i, Category.EXPERIENCES, SIXTY, Classification.EXPLICIT, 0.9 - i * 0.05);
            m.setUpdatedAt(NOW);
            usable.add(m);
        }

        MemoryContext ctx = service(props).select(usable, FakeEmbeddings.BASE, NOW);

        // each line "- [stated] <60 chars>" costs 18 tokens + 2 overhead, so a 40-token budget fits exactly two
        assertEquals(List.of("m0", "m1"), ids(ctx));
    }

    @Test
    void aShorterLowerRankedMemoryMayFillTheRemainingBudget() {
        MemoryProperties props = new MemoryProperties(0, 0, 0, 0, 30, 0, 0, 0, 0, 0, 0, 0, 0);
        MemoryDocument big = at("big", Category.EXPERIENCES, "x".repeat(200), Classification.EXPLICIT, 0.95);
        MemoryDocument small = at("small", Category.EXPERIENCES, "Short note", Classification.EXPLICIT, 0.8);
        big.setUpdatedAt(NOW);
        small.setUpdatedAt(NOW);

        MemoryContext ctx = service(props).select(List.of(big, small), FakeEmbeddings.BASE, NOW);

        assertEquals(List.of("small"), ids(ctx));
    }

    @Test
    void rankedMemoriesRespectTheCountLimit() {
        MemoryProperties props = new MemoryProperties(0, 0, 0, 0, 100_000, 3, 0, 0, 0, 0, 0, 0, 0);
        List<MemoryDocument> usable = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            usable.add(at("m" + i, Category.EXPERIENCES, "Note " + i, Classification.EXPLICIT, 0.9));
        }

        assertEquals(3, ids(service(props).select(usable, FakeEmbeddings.BASE, NOW)).size());
    }

    @Test
    void wholePromptBlockStaysSmall() {
        List<MemoryDocument> usable = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            usable.add(at("c" + i, Category.ABOUT_ME, "About the reader " + i + " " + "detail ".repeat(8), Classification.EXPLICIT, 0.9));
            usable.add(at("r" + i, Category.EXPERIENCES, "Experience " + i + " " + "detail ".repeat(8), Classification.EXPLICIT, 0.9));
        }

        MemoryContext ctx = service().select(usable, FakeEmbeddings.BASE, NOW);

        int header = TokenEstimator.estimate(MemoryRetrievalService.HEADER);
        assertTrue(ctx.estimatedTokens() <= header + 150 + 350 + 30, "was " + ctx.estimatedTokens());
        assertTrue(ids(ctx).size() <= 8 + 8, "core items + at most 8 ranked");
    }

    @Test
    void memoriesBelowTheSimilarityFloorAreNotSurfaced() {
        MemoryDocument related = at("yes", Category.EXPERIENCES, "Related", Classification.EXPLICIT, 0.6);
        MemoryDocument unrelated = at("no", Category.EXPERIENCES, "Unrelated", Classification.EXPLICIT, 0.1);

        assertEquals(List.of("yes"), ids(service().select(List.of(related, unrelated), FakeEmbeddings.BASE, NOW)));
    }

    @Test
    void sensitiveMemoriesNeedMoreRelevanceToBeSurfaced() {
        MemoryDocument normal = at("n", Category.EXPERIENCES, "Normal", Classification.EXPLICIT, 0.45);
        MemoryDocument touchy = at("s", Category.EXPERIENCES, "Touchy", Classification.EXPLICIT, 0.45);
        touchy.setSensitivity(Sensitivity.SENSITIVE);
        MemoryDocument touchyButRelevant = at("s2", Category.EXPERIENCES, "Touchy but on topic", Classification.EXPLICIT, 0.8);
        touchyButRelevant.setSensitivity(Sensitivity.SENSITIVE);

        List<String> got = ids(service().select(List.of(normal, touchy, touchyButRelevant), FakeEmbeddings.BASE, NOW));

        assertTrue(got.contains("n"));
        assertFalse(got.contains("s"));
        assertTrue(got.contains("s2"));
    }

    @Test
    void inferredMemoriesAreLabelledAndNeverInTheCoreProfile() {
        MemoryDocument stated = at("a", Category.EXPERIENCES, "Ran a marathon", Classification.EXPLICIT, 0.9);
        MemoryDocument guess = at("b", Category.INTERESTS, "Seems to like running", Classification.INFERRED, 0.9);

        MemoryContext ctx = service().select(List.of(stated, guess), FakeEmbeddings.BASE, NOW);

        assertTrue(ctx.promptBlock().contains("- [stated] Ran a marathon"));
        assertTrue(ctx.promptBlock().contains("- [inferred, unconfirmed] Seems to like running"));
        assertFalse(ctx.promptBlock().contains("Core profile"), "an unconfirmed inference is not part of the core profile");
    }

    @Test
    void noQueryMeansOnlyTheCoreProfile() {
        MemoryDocument about = at("a", Category.ABOUT_ME, "Is a teacher", Classification.EXPLICIT, 0.9);
        MemoryDocument other = at("b", Category.EXPERIENCES, "Ran a marathon", Classification.EXPLICIT, 0.9);

        assertEquals(List.of("a"), ids(service().select(List.of(about, other), null, NOW)));
    }

    @Test
    void nothingToSayYieldsTheEmptyContext() {
        assertTrue(service().select(List.of(), FakeEmbeddings.BASE, NOW).isEmpty());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Recency
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void recencyHalvesEveryHalfLifeWithAFloorAndPinnedOrVerifiedAreExempt() {
        MemoryRetrievalService s = service();
        MemoryDocument m = memory("m", Category.INTERESTS, "x", Classification.EXPLICIT);

        m.setUpdatedAt(NOW);
        assertEquals(1.0, s.recency(m, NOW), 1e-9);
        m.setUpdatedAt(NOW.minus(Duration.ofDays(45)));
        assertEquals(0.5, s.recency(m, NOW), 1e-6);
        m.setUpdatedAt(NOW.minus(Duration.ofDays(1000)));
        assertEquals(0.2, s.recency(m, NOW), 1e-9);

        m.setPinned(true);
        assertEquals(1.0, s.recency(m, NOW), 1e-9);
        m.setPinned(false);
        m.setUserVerified(true);
        assertEquals(1.0, s.recency(m, NOW), 1e-9);
    }

    // ---------------------------------------------------------------------------------------------------------
    // retrieve(): exclusion + resilience
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void retrievalNeverUsesArchivedExpiredOrSupersededMemories() {
        MemoryDocument live = at("live", Category.ABOUT_ME, "Is a teacher", Classification.EXPLICIT, 0.9);
        live.setUpdatedAt(java.time.Instant.now());
        MemoryDocument expired = at("expired", Category.ABOUT_ME, "Was feeling low", Classification.EXPLICIT, 0.9);
        expired.setExpiresAt(java.time.Instant.now().minusSeconds(60));
        MemoryDocument superseded = at("superseded", Category.ABOUT_ME, "Old fact", Classification.EXPLICIT, 0.9);
        superseded.setSupersededBy("live");
        MemoryDocument archived = at("archived", Category.ABOUT_ME, "Archived fact", Classification.EXPLICIT, 0.9);
        archived.setStatus(Status.ARCHIVED);
        // the repository query is by ACTIVE status, but the service must still defend itself
        when(repository.findByUserIdAndStatusOrderByUpdatedAtDesc(USER, Status.ACTIVE))
                .thenReturn(List.of(live, expired, superseded, archived));

        MemoryContext ctx = service().retrieve(USER, "what should I read next?");

        assertEquals(List.of("live"), ids(ctx));
        verify(mongo).updateMulti(any(Query.class), any(UpdateDefinition.class), eq(MemoryDocument.class));
    }

    @Test
    void retrievalFailureNeverBreaksChat() {
        when(repository.findByUserIdAndStatusOrderByUpdatedAtDesc(USER, Status.ACTIVE))
                .thenThrow(new IllegalStateException("db down"));

        MemoryContext ctx = service().retrieve(USER, "hello");

        assertTrue(ctx.isEmpty());
    }

    @Test
    void noMemoriesMeansNoWritesAtAll() {
        when(repository.findByUserIdAndStatusOrderByUpdatedAtDesc(USER, Status.ACTIVE)).thenReturn(List.of());

        assertTrue(service().retrieve(USER, "hello").isEmpty());
        verify(mongo, never()).updateMulti(any(Query.class), any(UpdateDefinition.class), any(Class.class));
    }
}
