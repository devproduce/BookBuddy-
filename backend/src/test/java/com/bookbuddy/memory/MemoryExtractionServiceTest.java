package com.bookbuddy.memory;

import static com.bookbuddy.memory.MemoryTestSupport.NOW;
import static com.bookbuddy.memory.MemoryTestSupport.USER;
import static com.bookbuddy.memory.MemoryTestSupport.candidate;
import static com.bookbuddy.memory.MemoryTestSupport.memory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.bookbuddy.ai.LlmException;
import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.auth.UserRepository;
import com.bookbuddy.conversation.ConversationDocument;
import com.bookbuddy.conversation.ConversationRepository;
import com.bookbuddy.conversation.MessageDocument;
import com.bookbuddy.conversation.MessageDocument.Role;
import com.bookbuddy.conversation.MessageDocument.Status;
import com.bookbuddy.conversation.MessageRepository;
import com.bookbuddy.jobs.JobHandler.BudgetDeferredException;
import com.bookbuddy.jobs.JobQueueDocument;
import com.bookbuddy.jobs.JobQueueDocument.Type;
import com.bookbuddy.jobs.JobQueueService;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Classification;
import com.bookbuddy.memory.MemoryTestSupport.FakeEmbeddings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Trigger policy, the incognito / paused skips, and "an LLM failure never changes or deletes anything". */
class MemoryExtractionServiceTest {

    private static final String CONV = "c1";

    private final UserRepository users = mock(UserRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final MemoryRepository memories = mock(MemoryRepository.class);
    private final MemoryExtractor extractor = mock(MemoryExtractor.class);
    private final JobQueueService jobs = mock(JobQueueService.class);
    private final LlmProvider llm = mock(LlmProvider.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final FakeEmbeddings embeddings = new FakeEmbeddings();

    private final UserDocument user = new UserDocument();
    private final ConversationDocument conversation = new ConversationDocument();
    private MemoryExtractionService service;

    @BeforeEach
    void setUp() {
        MemoryProperties props = MemoryProperties.defaults();
        service = new MemoryExtractionService(
                users, conversations, messages, memories, extractor,
                new MemoryMerger(embeddings, props), new MemoryWriter(memories), embeddings, jobs, llm, props, mongo, true);

        user.setId(USER);
        user.setMemoryEnabled(true);
        conversation.setId(CONV);
        conversation.setUserId(USER);
        conversation.setMessageCount(10);
        conversation.setUpdatedAt(NOW.minusSeconds(3600));

        when(llm.isConfigured()).thenReturn(true);
        when(users.findById(USER)).thenReturn(java.util.Optional.of(user));
        when(conversations.findByIdAndUserId(CONV, USER)).thenReturn(java.util.Optional.of(conversation));
        when(messages.findByConversationIdAndUserIdOrderByCreatedAtDescIdDesc(eq(CONV), eq(USER), any(Limit.class)))
                .thenReturn(List.of(msg("m2", Role.ASSISTANT, "Nice!", 2), msg("m1", Role.USER, "I love fantasy", 1)));
        when(memories.findByUserIdAndStatusOrderByUpdatedAtDesc(USER, MemoryDocument.Status.ACTIVE)).thenReturn(List.of());
    }

    private static MessageDocument msg(String id, Role role, String text, int n) {
        MessageDocument m = new MessageDocument();
        m.setId(id);
        m.setRole(role);
        m.setContent(text);
        m.setStatus(Status.COMPLETE);
        m.setCreatedAt(NOW.minusSeconds(1000 - n));
        return m;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Triggers
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void queuesAnExtractionOnceEnoughNewMessagesHavePiledUp() {
        conversation.setMessageCount(6);
        service.onTurnCompleted(user, conversation);
        verify(jobs).enqueueOnce(eq(Type.MEMORY_EXTRACTION), eq(USER), eq(Map.of("conversationId", CONV)), eq("memory-extract:c1:6"));
    }

    @Test
    void doesNotQueueBeforeTheThreshold() {
        conversation.setMessageCount(5);
        service.onTurnCompleted(user, conversation);
        verify(jobs, never()).enqueueOnce(any(), anyString(), anyMap(), anyString());
    }

    @Test
    void messagesAlreadyExtractedDoNotCountAsNew() {
        conversation.setMessageCount(10);
        conversation.setLastExtractedMessageCount(6);
        service.onTurnCompleted(user, conversation);
        verify(jobs, never()).enqueueOnce(any(), anyString(), anyMap(), anyString());
    }

    @Test
    void incognitoConversationsAreNeverQueued() {
        conversation.setIncognito(true);
        service.onTurnCompleted(user, conversation);
        verify(jobs, never()).enqueueOnce(any(), anyString(), anyMap(), anyString());
    }

    @Test
    void nothingIsQueuedWhenMemoryIsOffOrTheModelIsUnavailable() {
        user.setMemoryEnabled(false);
        service.onTurnCompleted(user, conversation);
        user.setMemoryEnabled(true);
        when(llm.isConfigured()).thenReturn(false);
        service.onTurnCompleted(user, conversation);
        verify(jobs, never()).enqueueOnce(any(), anyString(), anyMap(), anyString());
    }

    @Test
    void idleScanQueuesQuietConversationsWithNewMessagesForUsersWithMemoryOnly() {
        ConversationDocument off = new ConversationDocument();
        off.setId("c2");
        off.setUserId("u2");
        off.setMessageCount(3);
        UserDocument other = new UserDocument();
        other.setId("u2");
        other.setMemoryEnabled(false);
        ConversationDocument done = new ConversationDocument();
        done.setId("c3");
        done.setUserId(USER);
        done.setMessageCount(4);
        done.setLastExtractedMessageCount(4);
        when(users.findById("u2")).thenReturn(java.util.Optional.of(other));
        when(conversations.findByIncognitoFalseAndUpdatedAtBetween(any(Instant.class), any(Instant.class), any(Limit.class)))
                .thenReturn(List.of(conversation, off, done));

        int queued = service.scanIdle(NOW);

        assertEquals(1, queued);
        verify(jobs).enqueueOnce(eq(Type.MEMORY_EXTRACTION), eq(USER), anyMap(), eq("memory-extract:c1:10"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Skips
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void incognitoConversationsAreSkippedWithoutCallingTheModel() {
        conversation.setIncognito(true);

        var result = service.extractConversation(USER, CONV);

        assertTrue(result.skipped());
        verify(extractor, never()).extract(anyString(), any());
        verify(memories, never()).save(any());
    }

    @Test
    void pausedMemoryIsSkippedWithoutCallingTheModel() {
        user.setMemoryEnabled(false);

        var result = service.extractConversation(USER, CONV);

        assertTrue(result.skipped());
        assertEquals("memory paused", result.reason());
        verify(extractor, never()).extract(anyString(), any());
        verify(memories, never()).save(any());
    }

    @Test
    void aMissingConversationIsASilentSkip() {
        when(conversations.findByIdAndUserId(CONV, USER)).thenReturn(java.util.Optional.empty());
        assertTrue(service.extractConversation(USER, CONV).skipped());
    }

    @Test
    void memoryTurnedOffWhileTheModelWasThinkingDiscardsTheResult() {
        when(extractor.extract(eq(USER), any())).thenAnswer(inv -> {
            user.setMemoryEnabled(false);
            return List.of(candidate(Category.INTERESTS, "Loves fantasy", Classification.EXPLICIT));
        });

        var result = service.extractConversation(USER, CONV);

        assertTrue(result.skipped());
        verify(memories, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Safety of writes
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void anLlmFailureLeavesEveryMemoryUntouchedAndTheMessagesUnmarked() {
        when(extractor.extract(eq(USER), any())).thenThrow(new LlmException("timeout"));

        assertThrows(LlmException.class, () -> service.extractConversation(USER, CONV));

        verify(memories, never()).save(any());
        verify(memories, never()).delete(any());
        verify(memories, never()).deleteAll(any(Iterable.class));
        verify(memories, never()).deleteByUserId(anyString());
        verify(mongo, never()).updateFirst(any(), any(), eq(ConversationDocument.class));
    }

    @Test
    void invalidOutputAndDeferredBudgetAlsoWriteNothing() {
        when(extractor.extract(eq(USER), any()))
                .thenThrow(new MemoryExtractor.InvalidOutputException("bad"))
                .thenThrow(new BudgetDeferredException("low"));

        assertThrows(MemoryExtractor.InvalidOutputException.class, () -> service.extractConversation(USER, CONV));
        assertThrows(BudgetDeferredException.class, () -> service.extractConversation(USER, CONV));

        verify(memories, never()).save(any());
        verify(mongo, never()).updateFirst(any(), any(), eq(ConversationDocument.class));
    }

    @Test
    void successfulRunsWriteNewMemoriesAndMarkTheConversation() {
        when(extractor.extract(eq(USER), any()))
                .thenReturn(List.of(candidate(Category.INTERESTS, "Loves fantasy", Classification.EXPLICIT)));

        var result = service.extractConversation(USER, CONV);

        assertFalse(result.skipped());
        assertEquals(1, result.added());
        verify(memories).save(any(MemoryDocument.class));
        verify(mongo).updateFirst(any(), any(), eq(ConversationDocument.class));
    }

    @Test
    void duplicatesAreIgnoredAndNotWrittenAgain() {
        MemoryDocument existing = memory("old", Category.INTERESTS, "Loves fantasy", Classification.EXPLICIT);
        when(memories.findByUserIdAndStatusOrderByUpdatedAtDesc(USER, MemoryDocument.Status.ACTIVE))
                .thenReturn(List.of(existing));
        when(extractor.extract(eq(USER), any()))
                .thenReturn(List.of(candidate(Category.INTERESTS, "Loves fantasy", Classification.EXPLICIT)));

        var result = service.extractConversation(USER, CONV);

        assertEquals(1, result.ignored());
        verify(memories, never()).save(any());
    }

    @Test
    void jobsWithoutAConversationIdFailPermanently() {
        JobQueueDocument job = new JobQueueDocument();
        job.setType(Type.MEMORY_EXTRACTION);
        job.setUserId(USER);
        assertThrows(com.bookbuddy.jobs.JobHandler.PermanentJobException.class, () -> service.handle(job));
    }

    @Test
    void deletingAConversationDetachesButKeepsItsMemories() {
        MemoryDocument m = memory("m", Category.INTERESTS, "Loves fantasy", Classification.EXPLICIT);
        m.setSourceConversationId(CONV);
        m.setSourceMessageIds(new java.util.ArrayList<>(List.of("m1")));
        when(memories.findByUserIdAndSourceConversationId(USER, CONV)).thenReturn(List.of(m));

        service.onConversationDeleted(USER, CONV);

        verify(memories).save(m);
        assertEquals(null, m.getSourceConversationId());
        assertTrue(m.getSourceMessageIds().isEmpty());
    }
}
