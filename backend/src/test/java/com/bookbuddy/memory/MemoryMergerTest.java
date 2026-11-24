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
import com.bookbuddy.memory.MemoryDocument.SourceType;
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

    private MemoryDocument existing(String id, Category category, String content, SourceType SourceType) {
        MemoryDocument m = memory(id, category, content, SourceType);
        working.add(m);
        return m;
    }

    // ---------------------------------------------------------------------------------------------------------
    // ADD
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void addsWhenNothingIsKnownYet() {
        Outcome o = merge(candidate(Category.INTEREST, "Loves cosy mysteries", SourceType.EXPLICIT), 1.0);

        assertEquals(Action.ADD, o.action());
        assertNotNull(o.created());
        assertEquals(1, working.size());
        MemoryDocument m = o.created();
        assertEquals(USER, m.getUserId());
        assertEquals(Status.ACTIVE, m.getStatus());
        assertEquals("conv1", m.getSourceConversationIds().get(0));
        assertEquals("fake", m.getEmbeddingModel());
        assertFalse(m.getEmbedding().isEmpty());
        assertNotNull(m.getId(), "id is generated up front so SUPERSEDE can link old -> new");
    }

    @Test
    void addsWhenTheClosestMemoryIsOnlyLooselyRelated() {
        existing("m1", Category.INTEREST, "Likes fantasy", SourceType.EXPLICIT);

        Outcome o = merge(candidate(Category.INTEREST, "Likes sailing", SourceType.EXPLICIT), 0.5);

        assertEquals(Action.ADD, o.action());
        assertEquals(2, working.size());
    }

    @Test
    void sameWordsInADifferentCategoryAreAddedUnlessNearlyIdentical() {
        existing("m1", Category.INTEREST, "Likes long novels", SourceType.EXPLICIT);

        assertEquals(Action.ADD,
                merge(candidate(Category.PREFERENCE, "Prefers long novels", SourceType.EXPLICIT), 0.8).action());
        assertEquals(Action.IGNORE,
                merge(candidate(Category.PREFERENCE, "Likes long novels!", SourceType.EXPLICIT), 0.97).action());
    }

    // ---------------------------------------------------------------------------------------------------------
    // IGNORE / UPDATE / MERGE
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void ignoresADuplicateAndChangesNothing() {
        MemoryDocument m = existing("m1", Category.INTEREST, "Loves cosy mysteries", SourceType.EXPLICIT);

        Outcome o = merge(candidate(Category.INTEREST, "Really enjoys cosy mystery novels", SourceType.EXPLICIT), 0.95);

        assertEquals(Action.IGNORE, o.action());
        assertNull(o.created());
        assertNull(o.changed());
        assertEquals("Loves cosy mysteries", m.getContent());
        assertEquals(NOW.minusSeconds(3600), m.getUpdatedAt());
    }

    @Test
    void updatesWhenTheNewStatementContainsTheOldOne() {
        MemoryDocument m = existing("m1", Category.GOAL, "Wants to read more", SourceType.EXPLICIT);

        Outcome o = merge(candidate(Category.GOAL, "Wants to read more, aiming for 30 books this year", SourceType.EXPLICIT), 0.8);

        assertEquals(Action.UPDATE, o.action());
        assertSame(m, o.changed());
        assertEquals("Wants to read more, aiming for 30 books this year", m.getContent());
        assertEquals(NOW, m.getUpdatedAt());
    }

    @Test
    void mergesTwoRelatedStatementsIntoOne() {
        MemoryDocument m = existing("m1", Category.INTEREST, "Likes fantasy", SourceType.EXPLICIT);
        m.setSourceMessageIds(new ArrayList<>(List.of("a")));
        MemoryCandidate c = new MemoryCandidate(Category.INTEREST, "Enjoys epic sagas", SourceType.EXPLICIT, 0.9, 0.8, 1, com.bookbuddy.memory.MemoryDocument.Stability.EVOLVING, false, false, false, "".isEmpty() ? List.of() : List.of(""), List.of("a", "b"), List.of());

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
        MemoryDocument old = existing("m1", Category.PREFERENCE, "Prefers audiobooks", SourceType.EXPLICIT);

        Outcome o = merge(replacing(Category.PREFERENCE, "No longer likes audiobooks"), 0.8);

        assertEquals(Action.SUPERSEDE, o.action());
        assertSame(old, o.superseded());
        assertEquals(Status.ARCHIVED, old.getStatus());
        assertEquals(o.created().getId(), old.getSupersededBy());
        assertTrue(working.contains(o.created()));
        assertFalse(working.contains(old));
    }

    @Test
    void aCorrectionIsNotMistakenForADuplicateJustBecauseItEmbedsClose() {
        existing("m1", Category.PREFERENCE, "Likes audiobooks", SourceType.EXPLICIT);

        Outcome o = merge(replacing(Category.PREFERENCE, "Does not like audiobooks"), 0.96);

        assertEquals(Action.SUPERSEDE, o.action());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Inferred vs explicit
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void anInferenceNeverOverwritesOrMergesIntoWhatTheUserStated() {
        MemoryDocument stated = existing("m1", Category.INTEREST, "Loves fantasy", SourceType.EXPLICIT);

        Outcome o = merge(candidate(Category.INTEREST, "Seems to enjoy grimdark settings", SourceType.INFERRED), 0.8);

        assertEquals(Action.ADD, o.action(), "kept as its own, clearly inferred memory");
        assertEquals(SourceType.INFERRED, o.created().getSourceType());
        assertEquals("Loves fantasy", stated.getContent());
        assertEquals(SourceType.EXPLICIT, stated.getSourceType());
        assertEquals(1.0, stated.getConfidence(), 1e-9);
    }

    @Test
    void anInferenceThatRepeatsAStatedMemoryIsIgnoredRatherThanCountedAsConfirmation() {
        MemoryDocument stated = existing("m1", Category.INTEREST, "Loves fantasy", SourceType.EXPLICIT);

        Outcome o = merge(candidate(Category.INTEREST, "Probably likes fantasy", SourceType.INFERRED), 0.95);

        assertEquals(Action.IGNORE, o.action());
        assertFalse(stated.isUserConfirmed());
        assertEquals(SourceType.EXPLICIT, stated.getSourceType());
    }

    @Test
    void anInferenceNeverSupersedesAVerifiedMemory() {
        MemoryDocument verified = existing("m1", Category.PREFERENCE, "Prefers paperbacks", SourceType.INFERRED);
        verified.setSourceType(SourceType.USER_CONFIRMED);
        MemoryCandidate c = new MemoryCandidate(Category.PREFERENCE, "Seems to prefer e-books", SourceType.INFERRED, 0.6, 0.5, 1, com.bookbuddy.memory.MemoryDocument.Stability.EVOLVING, false, true, false, "".isEmpty() ? List.of() : List.of(""), List.of(), List.of());

        Outcome o = merge(c, 0.8);

        assertNotEquals(Action.SUPERSEDE, o.action());
        assertEquals(Status.ACTIVE, verified.getStatus());
    }

    private static void assertNotEquals(Object unexpected, Object actual) {
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual);
    }

    @Test
    void anExplicitStatementReplacesAnUnconfirmedInference() {
        MemoryDocument guess = existing("m1", Category.INTEREST, "Probably likes thrillers", SourceType.INFERRED);

        Outcome o = merge(candidate(Category.INTEREST, "Loves thrillers", SourceType.EXPLICIT), 0.85);

        assertEquals(Action.UPDATE, o.action());
        assertSame(guess, o.changed());
        assertEquals("Loves thrillers", guess.getContent());
        assertEquals(SourceType.EXPLICIT, guess.getSourceType());
    }

    @Test
    void mergedInferencesStayInferredAndCappedAtSeventyPercent() {
        MemoryDocument m = existing("m1", Category.PERSONALITY_OBSERVATION, "Often reads late at night", SourceType.INFERRED);
        m.setConfidence(0.6);
        MemoryCandidate c = candidate(Category.PERSONALITY_OBSERVATION, "Tends to ask for short chapters", SourceType.INFERRED);
        c = new MemoryCandidate(
                c.category(), c.content(), c.sourceType(), 0.99, 0.5, 1, c.stability(), false, false, false,
                List.of(), List.of(), List.of());

        Outcome o = merge(c, 0.8);

        assertEquals(Action.MERGE, o.action());
        assertEquals(SourceType.INFERRED, m.getSourceType());
        assertFalse(m.isUserConfirmed());
        assertEquals(MemoryPolicy.INFERRED_CONFIDENCE_CAP, m.getConfidence(), 1e-9);
    }

    @Test
    void newInferredMemoriesAreCappedEvenIfTheCandidateSlippedThroughHigher() {
        MemoryCandidate c = new MemoryCandidate(Category.PERSONALITY_OBSERVATION, "Reads in bursts", SourceType.INFERRED, 0.95, 0.5, 1, com.bookbuddy.memory.MemoryDocument.Stability.EVOLVING, false, false, false, "".isEmpty() ? List.of() : List.of(""), List.of(), List.of());

        Outcome o = merge(c, 1.0);

        assertEquals(MemoryPolicy.INFERRED_CONFIDENCE_CAP, o.created().getConfidence(), 1e-9);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hygiene
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void archivedExpiredAndSupersededMemoriesAreNotMergeTargets() {
        MemoryDocument archived = existing("m1", Category.INTEREST, "Likes poetry", SourceType.EXPLICIT);
        archived.setStatus(Status.ARCHIVED);
        MemoryDocument expired = existing("m2", Category.INTEREST, "Likes essays", SourceType.EXPLICIT);
        expired.setExpiresAt(NOW.minusSeconds(60));
        MemoryDocument replaced = existing("m3", Category.INTEREST, "Likes plays", SourceType.EXPLICIT);
        replaced.setSupersededBy("m9");

        Outcome o = merge(candidate(Category.INTEREST, "Likes poetry a lot", SourceType.EXPLICIT), 0.99);

        assertEquals(Action.ADD, o.action());
    }

    @Test
    void emotionalContextExpiresInFourteenDaysAndIsRenewedWhenItComesUpAgain() {
        Outcome added = merge(candidate(Category.EMOTIONAL_CONTEXT, "Feeling stressed about exams", SourceType.EXPLICIT), 1.0);
        assertEquals(NOW.plus(Duration.ofDays(14)), added.created().getExpiresAt());

        MemoryDocument m = added.created();
        m.setExpiresAt(NOW.plus(Duration.ofDays(1)));
        MemoryCandidate again = candidate(Category.EMOTIONAL_CONTEXT, "Feeling stressed about exams, still", SourceType.EXPLICIT);
        Outcome o = merger.merge(again, USER, "conv2", FakeEmbeddings.at(0.8), working, NOW.plusSeconds(60));

        assertEquals(Action.UPDATE, o.action());
        assertEquals(NOW.plusSeconds(60).plus(Duration.ofDays(14)), m.getExpiresAt());
    }

    @Test
    void otherCategoriesNeverGetAnExpiry() {
        Outcome o = merge(candidate(Category.GOAL, "Wants to write a novel", SourceType.EXPLICIT), 1.0);
        assertNull(o.created().getExpiresAt());
    }

    @Test
    void sensitiveCandidatesAreFlaggedOnTheStoredMemory() {
        MemoryCandidate c = new MemoryCandidate(Category.PERSONAL_BACKGROUND, "Lives with anxiety", SourceType.EXPLICIT, 0.9, 0.8, 1, com.bookbuddy.memory.MemoryDocument.Stability.EVOLVING, true, false, false, "I live with anxiety".isEmpty() ? List.of() : List.of("I live with anxiety"), List.of(), List.of());

        Outcome o = merge(c, 1.0);

        assertEquals(Sensitivity.SENSITIVE, o.created().getSensitivity());
    }

    @Test
    void laterCandidatesInTheSameRunSeeEarlierOnes() {
        embeddings.put("Loves cosy mysteries", FakeEmbeddings.at(1.0));
        Outcome first = merge(candidate(Category.INTEREST, "Loves cosy mysteries", SourceType.EXPLICIT), 1.0);
        Outcome second = merge(candidate(Category.INTEREST, "Adores cosy mysteries", SourceType.EXPLICIT), 0.95);

        assertEquals(Action.ADD, first.action());
        assertEquals(Action.IGNORE, second.action());
        assertEquals(1, working.size());
    }

    @Test
    void lexicalEmbeddingsUseLowerThresholds() {
        embeddings.semantic = false;
        existing("m1", Category.INTEREST, "Loves cosy mysteries", SourceType.EXPLICIT);

        // 0.8 is only a "merge" for a semantic model, but a lexical (hash) model scores true duplicates lower
        Outcome o = merge(candidate(Category.INTEREST, "Loves cosy mystery books", SourceType.EXPLICIT), 0.8);

        assertEquals(Action.IGNORE, o.action());
    }

    @Test
    void combineKeepsTheLongerStatementWhenOneContainsTheOther() {
        assertEquals("Likes fantasy novels", MemoryMerger.combine("Likes fantasy", "Likes fantasy novels"));
        assertEquals("Likes fantasy novels", MemoryMerger.combine("Likes fantasy novels", "likes fantasy"));
    }
}
