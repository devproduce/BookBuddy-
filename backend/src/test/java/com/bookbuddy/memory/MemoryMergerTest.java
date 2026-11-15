package com.bookbuddy.memory;

import static com.bookbuddy.memory.MemoryTestSupport.NOW;
import static com.bookbuddy.memory.MemoryTestSupport.USER;
import static com.bookbuddy.memory.MemoryTestSupport.candidate;
import static com.bookbuddy.memory.MemoryTestSupport.memory;
import static com.bookbuddy.memory.MemoryTestSupport.replacing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Classification;
import com.bookbuddy.memory.MemoryDocument.Sensitivity;
import com.bookbuddy.memory.MemoryDocument.Status;
import com.bookbuddy.memory.MemoryMerger.Action;
import com.bookbuddy.memory.MemoryMerger.Outcome;
import com.bookbuddy.memory.MemoryTestSupport.FakeEmbeddings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryMergerTest {

    private FakeEmbeddings embeddings;
    private MemoryMerger merger;
    private List<MemoryDocument> working;

    @BeforeEach
    void setUp() {
        embeddings = new FakeEmbeddings();
        merger = new MemoryMerger(embeddings, MemoryProperties.defaults());
        working = new ArrayList<>();
    }

    private Outcome merge(MemoryCandidate c, double similarityToExisting) {
        return merger.merge(c, USER, "conv1", FakeEmbeddings.at(similarityToExisting), working, NOW);
    }

    private MemoryDocument existing(String id, Category category, String content, Classification classification) {
        MemoryDocument m = memory(id, category, content, classification);
        working.add(m);
        return m;
    }

    // ---------------------------------------------------------------------------------------------------------
    // ADD
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void addsWhenNothingIsKnownYet() {
        Outcome o = merge(candidate(Category.INTERESTS, "Loves cosy mysteries", Classification.EXPLICIT), 1.0);

        assertEquals(Action.ADD, o.action());
        assertNotNull(o.created());
        assertEquals(1, working.size());
        MemoryDocument m = o.created();
        assertEquals(USER, m.getUserId());
        assertEquals(Status.ACTIVE, m.getStatus());
        assertEquals("conv1", m.getSourceConversationId());
        assertEquals("fake", m.getEmbeddingModel());
        assertFalse(m.getEmbedding().isEmpty());
        assertNotNull(m.getId(), "id is generated up front so SUPERSEDE can link old -> new");
    }

    @Test
    void addsWhenTheClosestMemoryIsOnlyLooselyRelated() {
        existing("m1", Category.INTERESTS, "Likes fantasy", Classification.EXPLICIT);

        Outcome o = merge(candidate(Category.INTERESTS, "Likes sailing", Classification.EXPLICIT), 0.5);

        assertEquals(Action.ADD, o.action());
        assertEquals(2, working.size());
    }

    @Test
    void sameWordsInADifferentCategoryAreAddedUnlessNearlyIdentical() {
        existing("m1", Category.INTERESTS, "Likes long novels", Classification.EXPLICIT);

        assertEquals(Action.ADD,
                merge(candidate(Category.PREFERENCES, "Prefers long novels", Classification.EXPLICIT), 0.8).action());
        assertEquals(Action.IGNORE,
                merge(candidate(Category.PREFERENCES, "Likes long novels!", Classification.EXPLICIT), 0.97).action());
    }

    // ---------------------------------------------------------------------------------------------------------
    // IGNORE / UPDATE / MERGE
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void ignoresADuplicateAndChangesNothing() {
        MemoryDocument m = existing("m1", Category.INTERESTS, "Loves cosy mysteries", Classification.EXPLICIT);

        Outcome o = merge(candidate(Category.INTERESTS, "Really enjoys cosy mystery novels", Classification.EXPLICIT), 0.95);

        assertEquals(Action.IGNORE, o.action());
        assertNull(o.created());
        assertNull(o.changed());
        assertEquals("Loves cosy mysteries", m.getContent());
        assertEquals(NOW.minusSeconds(3600), m.getUpdatedAt());
    }

    @Test
    void updatesWhenTheNewStatementContainsTheOldOne() {
        MemoryDocument m = existing("m1", Category.GOALS, "Wants to read more", Classification.EXPLICIT);

        Outcome o = merge(candidate(Category.GOALS, "Wants to read more, aiming for 30 books this year", Classification.EXPLICIT), 0.8);

        assertEquals(Action.UPDATE, o.action());
        assertSame(m, o.changed());
        assertEquals("Wants to read more, aiming for 30 books this year", m.getContent());
        assertEquals(NOW, m.getUpdatedAt());
    }

    @Test
    void mergesTwoRelatedStatementsIntoOne() {
        MemoryDocument m = existing("m1", Category.INTERESTS, "Likes fantasy", Classification.EXPLICIT);
        m.setSourceMessageIds(new ArrayList<>(List.of("a")));
        MemoryCandidate c = new MemoryCandidate(Category.INTERESTS, "Enjoys epic sagas", Classification.EXPLICIT,
                0.9, 0.8, false, false, "", List.of("a", "b"));

        Outcome o = merge(c, 0.8);

        assertEquals(Action.MERGE, o.action());
        assertEquals("Likes fantasy; Enjoys epic sagas", m.getContent());
        assertEquals(List.of("a", "b"), m.getSourceMessageIds());
        assertEquals(0.8, m.getImportance(), 1e-9, "importance never goes down");
        assertEquals(1, working.size());
    }

    // ---------------------------------------------------------------------------------------------------------
    // SUPERSEDE
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void supersedesWhenTheUserChangesTheirMind() {
        MemoryDocument old = existing("m1", Category.PREFERENCES, "Prefers audiobooks", Classification.EXPLICIT);

        Outcome o = merge(replacing(Category.PREFERENCES, "No longer likes audiobooks"), 0.8);

        assertEquals(Action.SUPERSEDE, o.action());
        assertSame(old, o.superseded());
        assertEquals(Status.ARCHIVED, old.getStatus());
        assertEquals(o.created().getId(), old.getSupersededBy());
        assertTrue(working.contains(o.created()));
        assertFalse(working.contains(old));
    }

    @Test
    void aCorrectionIsNotMistakenForADuplicateJustBecauseItEmbedsClose() {
        existing("m1", Category.PREFERENCES, "Likes audiobooks", Classification.EXPLICIT);

        Outcome o = merge(replacing(Category.PREFERENCES, "Does not like audiobooks"), 0.96);

        assertEquals(Action.SUPERSEDE, o.action());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Inferred vs explicit
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void anInferenceNeverOverwritesOrMergesIntoWhatTheUserStated() {
        MemoryDocument stated = existing("m1", Category.INTERESTS, "Loves fantasy", Classification.EXPLICIT);

        Outcome o = merge(candidate(Category.INTERESTS, "Seems to enjoy grimdark settings", Classification.INFERRED), 0.8);

        assertEquals(Action.ADD, o.action(), "kept as its own, clearly inferred memory");
        assertEquals(Classification.INFERRED, o.created().getClassification());
        assertEquals("Loves fantasy", stated.getContent());
        assertEquals(Classification.EXPLICIT, stated.getClassification());
        assertEquals(1.0, stated.getConfidence(), 1e-9);
    }

    @Test
    void anInferenceThatRepeatsAStatedMemoryIsIgnoredRatherThanCountedAsConfirmation() {
        MemoryDocument stated = existing("m1", Category.INTERESTS, "Loves fantasy", Classification.EXPLICIT);

        Outcome o = merge(candidate(Category.INTERESTS, "Probably likes fantasy", Classification.INFERRED), 0.95);

        assertEquals(Action.IGNORE, o.action());
        assertFalse(stated.isUserVerified());
        assertEquals(Classification.EXPLICIT, stated.getClassification());
    }

    @Test
    void anInferenceNeverSupersedesAVerifiedMemory() {
        MemoryDocument verified = existing("m1", Category.PREFERENCES, "Prefers paperbacks", Classification.INFERRED);
        verified.setUserVerified(true);
        MemoryCandidate c = new MemoryCandidate(Category.PREFERENCES, "Seems to prefer e-books", Classification.INFERRED,
                0.6, 0.5, false, true, "", List.of());

        Outcome o = merge(c, 0.8);

        assertNotEquals(Action.SUPERSEDE, o.action());
        assertEquals(Status.ACTIVE, verified.getStatus());
    }

    private static void assertNotEquals(Object unexpected, Object actual) {
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual);
    }

    @Test
    void anExplicitStatementReplacesAnUnconfirmedInference() {
        MemoryDocument guess = existing("m1", Category.INTERESTS, "Probably likes thrillers", Classification.INFERRED);

        Outcome o = merge(candidate(Category.INTERESTS, "Loves thrillers", Classification.EXPLICIT), 0.85);

        assertEquals(Action.UPDATE, o.action());
        assertSame(guess, o.changed());
        assertEquals("Loves thrillers", guess.getContent());
        assertEquals(Classification.EXPLICIT, guess.getClassification());
    }

    @Test
    void mergedInferencesStayInferredAndCappedAtSeventyPercent() {
        MemoryDocument m = existing("m1", Category.OBSERVED_PATTERNS, "Often reads late at night", Classification.INFERRED);
        m.setConfidence(0.6);
        MemoryCandidate c = new MemoryCandidate(Category.OBSERVED_PATTERNS, "Tends to ask for short chapters",
                Classification.INFERRED, 0.99, 0.5, false, false, "", List.of());

        Outcome o = merge(c, 0.8);

        assertEquals(Action.MERGE, o.action());
        assertEquals(Classification.INFERRED, m.getClassification());
        assertFalse(m.isUserVerified());
        assertEquals(MemoryPolicy.INFERRED_CONFIDENCE_CAP, m.getConfidence(), 1e-9);
    }

    @Test
    void newInferredMemoriesAreCappedEvenIfTheCandidateSlippedThroughHigher() {
        MemoryCandidate c = new MemoryCandidate(Category.OBSERVED_PATTERNS, "Reads in bursts", Classification.INFERRED,
                0.95, 0.5, false, false, "", List.of());

        Outcome o = merge(c, 1.0);

        assertEquals(MemoryPolicy.INFERRED_CONFIDENCE_CAP, o.created().getConfidence(), 1e-9);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hygiene
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void archivedExpiredAndSupersededMemoriesAreNotMergeTargets() {
        MemoryDocument archived = existing("m1", Category.INTERESTS, "Likes poetry", Classification.EXPLICIT);
        archived.setStatus(Status.ARCHIVED);
        MemoryDocument expired = existing("m2", Category.INTERESTS, "Likes essays", Classification.EXPLICIT);
        expired.setExpiresAt(NOW.minusSeconds(60));
        MemoryDocument replaced = existing("m3", Category.INTERESTS, "Likes plays", Classification.EXPLICIT);
        replaced.setSupersededBy("m9");

        Outcome o = merge(candidate(Category.INTERESTS, "Likes poetry a lot", Classification.EXPLICIT), 0.99);

        assertEquals(Action.ADD, o.action());
    }

    @Test
    void emotionalContextExpiresInFourteenDaysAndIsRenewedWhenItComesUpAgain() {
        Outcome added = merge(candidate(Category.EMOTIONAL_CONTEXT, "Feeling stressed about exams", Classification.EXPLICIT), 1.0);
        assertEquals(NOW.plus(Duration.ofDays(14)), added.created().getExpiresAt());

        MemoryDocument m = added.created();
        m.setExpiresAt(NOW.plus(Duration.ofDays(1)));
        MemoryCandidate again = candidate(Category.EMOTIONAL_CONTEXT, "Feeling stressed about exams, still", Classification.EXPLICIT);
        Outcome o = merger.merge(again, USER, "conv2", FakeEmbeddings.at(0.8), working, NOW.plusSeconds(60));

        assertEquals(Action.UPDATE, o.action());
        assertEquals(NOW.plusSeconds(60).plus(Duration.ofDays(14)), m.getExpiresAt());
    }

    @Test
    void otherCategoriesNeverGetAnExpiry() {
        Outcome o = merge(candidate(Category.GOALS, "Wants to write a novel", Classification.EXPLICIT), 1.0);
        assertNull(o.created().getExpiresAt());
    }

    @Test
    void sensitiveCandidatesAreFlaggedOnTheStoredMemory() {
        MemoryCandidate c = new MemoryCandidate(Category.ABOUT_ME, "Lives with anxiety", Classification.EXPLICIT,
                0.9, 0.8, true, false, "I live with anxiety", List.of());

        Outcome o = merge(c, 1.0);

        assertEquals(Sensitivity.SENSITIVE, o.created().getSensitivity());
    }

    @Test
    void laterCandidatesInTheSameRunSeeEarlierOnes() {
        embeddings.put("Loves cosy mysteries", FakeEmbeddings.at(1.0));
        Outcome first = merge(candidate(Category.INTERESTS, "Loves cosy mysteries", Classification.EXPLICIT), 1.0);
        Outcome second = merge(candidate(Category.INTERESTS, "Adores cosy mysteries", Classification.EXPLICIT), 0.95);

        assertEquals(Action.ADD, first.action());
        assertEquals(Action.IGNORE, second.action());
        assertEquals(1, working.size());
    }

    @Test
    void lexicalEmbeddingsUseLowerThresholds() {
        embeddings.semantic = false;
        existing("m1", Category.INTERESTS, "Loves cosy mysteries", Classification.EXPLICIT);

        // 0.8 is only a "merge" for a semantic model, but a lexical (hash) model scores true duplicates lower
        Outcome o = merge(candidate(Category.INTERESTS, "Loves cosy mystery books", Classification.EXPLICIT), 0.8);

        assertEquals(Action.IGNORE, o.action());
    }

    @Test
    void combineKeepsTheLongerStatementWhenOneContainsTheOther() {
        assertEquals("Likes fantasy novels", MemoryMerger.combine("Likes fantasy", "Likes fantasy novels"));
        assertEquals("Likes fantasy novels", MemoryMerger.combine("Likes fantasy novels", "likes fantasy"));
    }
}
