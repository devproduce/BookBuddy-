package com.bookbuddy.memory;

import static com.bookbuddy.memory.MemoryTestSupport.NOW;
import static com.bookbuddy.memory.MemoryTestSupport.USER;
import static com.bookbuddy.memory.MemoryTestSupport.memory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.auth.UserRepository;
import com.bookbuddy.common.ApiException;
import com.bookbuddy.jobs.JobQueueDocument.Type;
import com.bookbuddy.jobs.JobQueueService;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.SourceType;
import com.bookbuddy.memory.MemoryDocument.Status;
import com.bookbuddy.memory.MemoryDtos.CreateMemoryRequest;
import com.bookbuddy.memory.MemoryDtos.MemoryResponse;
import com.bookbuddy.memory.MemoryDtos.UpdateMemoryRequest;
import com.bookbuddy.memory.MemoryTestSupport.FakeEmbeddings;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The user-facing memory operations: expiry sweep, manual edits, confirmation, rebuild preconditions. */
class MemoryServiceTest {

    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final MemoryRetrievalService retrieval = mock(MemoryRetrievalService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final JobQueueService jobs = mock(JobQueueService.class);
    private final LlmProvider llm = mock(LlmProvider.class);
    private final UserDocument user = new UserDocument();
    private MemoryService service;

    @BeforeEach
    void setUp() {
        service = new MemoryService(
                repository, profiles, new FakeEmbeddings(), retrieval, MemoryProperties.defaults(), users, jobs, llm);
        user.setId(USER);
        user.setMemoryEnabled(true);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        when(llm.isConfigured()).thenReturn(true);
        when(repository.save(any(MemoryDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByUserIdAndStatusOrderByUpdatedAtDesc(anyString(), any())).thenReturn(List.of());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Expiry
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void expirySweepMarksOverdueMemoriesExpiredButSkipsPinnedOnes() {
        MemoryDocument due = memory("a", Category.EMOTIONAL_CONTEXT, "Stressed", SourceType.EXPLICIT);
        due.setExpiresAt(NOW.minusSeconds(5));
        MemoryDocument pinned = memory("b", Category.EMOTIONAL_CONTEXT, "Anxious about move", SourceType.EXPLICIT);
        pinned.setExpiresAt(NOW.minusSeconds(5));
        pinned.setPinned(true);
        when(repository.findByStatusAndExpiresAtLessThan(Status.ACTIVE, NOW)).thenReturn(List.of(due, pinned));

        int n = service.expireDue(NOW);

        assertEquals(1, n);
        assertEquals(Status.EXPIRED, due.getStatus());
        assertEquals(Status.ACTIVE, pinned.getStatus());
        verify(repository).save(due);
        verify(repository, never()).save(pinned);
    }

    @Test
    void anOverdueMemoryIsShownAsExpiredEvenBeforeTheSweepRuns() {
        MemoryDocument m = memory("a", Category.EMOTIONAL_CONTEXT, "Stressed", SourceType.EXPLICIT);
        m.setExpiresAt(Instant.now().minusSeconds(60));

        assertEquals(Status.EXPIRED, MemoryResponse.from(m, Instant.now()).status());
        m.setPinned(true);
        assertEquals(Status.ACTIVE, MemoryResponse.from(m, Instant.now()).status());
    }

    @Test
    void responsesNeverExposeEmbeddings() {
        MemoryResponse r = MemoryResponse.from(memory("a", Category.INTEREST, "Likes poetry", SourceType.EXPLICIT), NOW);
        for (var component : MemoryResponse.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase().contains("embedding"), component.getName());
        }
        assertNotNull(r);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Manual create / edit / confirm
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void manuallyAddedMemoriesAreExplicitVerifiedAndEmotionalOnesStillExpire() {
        MemoryResponse r = service.create(USER, new CreateMemoryRequest(Category.EMOTIONAL_CONTEXT, "Nervous about a job interview", false));

        assertEquals(SourceType.EXPLICIT, r.sourceType());
        assertNotNull(r.expiresAt());
        assertEquals(1.0, r.confidence(), 1e-9);
    }

    @Test
    void pinnedEmotionalContextHasNoExpiry() {
        MemoryResponse r = service.create(USER, new CreateMemoryRequest(Category.EMOTIONAL_CONTEXT, "Grieving a loss", true));
        assertNull(r.expiresAt());
        assertTrue(r.pinned());
    }

    @Test
    void duplicatesAreRejected() {
        when(repository.findByUserIdAndStatusOrderByUpdatedAtDesc(USER, Status.ACTIVE))
                .thenReturn(List.of(memory("a", Category.INTEREST, "Likes poetry", SourceType.EXPLICIT)));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(USER, new CreateMemoryRequest(Category.INTEREST, "likes  POETRY!", null)));
        assertEquals("MEMORY_DUPLICATE", ex.getCode());
    }

    @Test
    void editingTheTextMakesItStatedAndVerified() {
        MemoryDocument guess = memory("a", Category.INTEREST, "Probably likes poetry", SourceType.INFERRED);
        when(repository.findByIdAndUserId("a", USER)).thenReturn(Optional.of(guess));

        service.update(USER, "a", new UpdateMemoryRequest(null, "Loves haiku", null, null));

        ArgumentCaptor<MemoryDocument> saved = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(repository).save(saved.capture());
        assertEquals("Loves haiku", saved.getValue().getContent());
        assertEquals(SourceType.EXPLICIT, saved.getValue().getSourceType());
        assertTrue(saved.getValue().isStated());
    }

    @Test
    void pinningAnEmotionalMemoryClearsItsExpiryAndUnpinningRestoresIt() {
        MemoryDocument m = memory("a", Category.EMOTIONAL_CONTEXT, "Stressed", SourceType.EXPLICIT);
        m.setExpiresAt(Instant.now().plus(Duration.ofDays(3)));
        when(repository.findByIdAndUserId("a", USER)).thenReturn(Optional.of(m));

        service.update(USER, "a", new UpdateMemoryRequest(null, null, true, null));
        assertNull(m.getExpiresAt());

        service.update(USER, "a", new UpdateMemoryRequest(null, null, false, null));
        assertNotNull(m.getExpiresAt());
    }

    @Test
    void archivedMemoriesCannotBeEdited() {
        MemoryDocument m = memory("a", Category.INTEREST, "Old", SourceType.EXPLICIT);
        m.setStatus(Status.ARCHIVED);
        when(repository.findByIdAndUserId("a", USER)).thenReturn(Optional.of(m));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.update(USER, "a", new UpdateMemoryRequest(null, "New text", null, null)));
        assertEquals("MEMORY_NOT_ACTIVE", ex.getCode());
    }

    @Test
    void confirmingLiftsTheInferredCapAndMarksItVerified() {
        MemoryDocument guess = memory("a", Category.INTEREST, "Probably likes poetry", SourceType.INFERRED);
        when(repository.findByIdAndUserId("a", USER)).thenReturn(Optional.of(guess));

        MemoryResponse r = service.confirm(USER, "a");

        assertEquals(SourceType.USER_CONFIRMED, r.sourceType());
        assertEquals(1.0, r.confidence(), 1e-9);
    }

    @Test
    void deletingIsAHardDelete() {
        MemoryDocument m = memory("a", Category.INTEREST, "Likes poetry", SourceType.EXPLICIT);
        when(repository.findByIdAndUserId("a", USER)).thenReturn(Optional.of(m));

        service.delete(USER, "a");

        verify(repository).delete(m);
    }

    @Test
    void someoneElsesMemoryIsNotFound() {
        when(repository.findByIdAndUserId("a", "intruder")).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class, () -> service.delete("intruder", "a"));
        assertEquals(404, ex.getStatus().value());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rebuild
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void rebuildIsRefusedWhileMemoryIsOffOrTheModelIsUnavailable() {
        user.setMemoryEnabled(false);
        assertEquals("MEMORY_PAUSED", assertThrows(ApiException.class, () -> service.rebuild(USER)).getCode());

        user.setMemoryEnabled(true);
        when(llm.isConfigured()).thenReturn(false);
        assertEquals("LLM_UNAVAILABLE", assertThrows(ApiException.class, () -> service.rebuild(USER)).getCode());
        verify(jobs, never()).enqueue(any(), anyString(), anyMap(), anyString());
    }

    @Test
    void rebuildQueuesADurableJobDedupedPerUser() {
        com.bookbuddy.jobs.JobQueueDocument queued = new com.bookbuddy.jobs.JobQueueDocument();
        queued.setId("job1");
        queued.setType(Type.MEMORY_REBUILD);
        queued.setStatus(com.bookbuddy.jobs.JobQueueDocument.Status.PENDING);
        when(jobs.enqueue(eq(Type.MEMORY_REBUILD), eq(USER), anyMap(), eq("memory-rebuild:" + USER))).thenReturn(queued);

        assertEquals("job1", service.rebuild(USER).jobId());
    }

    @Test
    void parseIdsSplitsTrimsAndDropsBlanks() {
        assertEquals(List.of("a", "b", "c"), List.copyOf(MemoryService.parseIds(" a, b ,,c ")));
        assertEquals(List.of("a1"), List.copyOf(MemoryService.parseIds("a1,bad id!,../x")), "only plain ids are accepted");
        assertTrue(MemoryService.parseIds(null).isEmpty());
        assertTrue(MemoryService.parseIds("").isEmpty());
    }
}
