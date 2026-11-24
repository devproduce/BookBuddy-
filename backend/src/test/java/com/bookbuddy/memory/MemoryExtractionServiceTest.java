package com.bookbuddy.memory;

import static com.bookbuddy.memory.MemoryTestSupport.NOW;
import static com.bookbuddy.memory.MemoryTestSupport.USER;
import static com.bookbuddy.memory.MemoryTestSupport.memory;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.bookbuddy.conversation.ConversationDocument;
import com.bookbuddy.conversation.ConversationRepository;
import com.bookbuddy.conversation.MessageDocument;
import com.bookbuddy.conversation.MessageDocument.Role;
import com.bookbuddy.conversation.MessageDocument.Status;
import com.bookbuddy.conversation.MessageRepository;
import com.bookbuddy.jobs.JobQueueDocument;
import com.bookbuddy.jobs.JobQueueDocument.Type;
import com.bookbuddy.jobs.JobQueueService;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.SourceType;
import com.bookbuddy.memory.MemoryEngineResult.Operation;
import com.bookbuddy.memory.MemoryTestSupport.FakeEmbeddings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Trigger policy, the incognito / paused skips, and engine-driven apply. */
class MemoryExtractionServiceTest {

    private static final String CONV = "c1";

    private final UserRepository users = mock(UserRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final MemoryRepository memories = mock(MemoryRepository.class);
    private final MemoryEngineClient engine = mock(MemoryEngineClient.class);
    private final MemoryApplyService apply = mock(MemoryApplyService.class);
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
                users,
                conversations,
                messages,
                memories,
                engine,
                apply,
                embeddings,
                jobs,
                llm,
                props,
                mongo,
                true);

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
        when(memories.countByUserIdAndStatus(USER, MemoryDocument.Status.ACTIVE)).thenReturn(0L);
        when(apply.apply(anyString(), anyString(), any())).thenReturn(
                new MemoryApplyService.ApplyResult(1, 0, 0, 0, 0, 0, 0, false, List.of()));
        when(engine.analyzeIncremental(anyString(), anyString(), any(), any())).thenReturn(emptyResult());
    }

    private static MemoryEngineResult emptyResult() {
        return new MemoryEngineResult(
                Operation.INCREMENTAL_ANALYSIS,
                MemoryEngineResult.ProfileDraft.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                MemoryEngineResult.InteractionDraft.empty(),
                MemoryEngineResult.ProcessingMetadata.empty(),
                List.of(),
                "");
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
    void skipsIncognitoAndPausedUsers() {
        conversation.setIncognito(true);
        service.onTurnCompleted(user, conversation);
        verify(jobs, never()).enqueueOnce(any(), anyString(), anyMap(), anyString());

        conversation.setIncognito(false);
        user.setMemoryEnabled(false);
        service.onTurnCompleted(user, conversation);
        verify(jobs, never()).enqueueOnce(any(), anyString(), anyMap(), anyString());
    }

    @Test
    void extractConversationAppliesEngineResult() {
        var result = service.extractConversation(USER, CONV);
        assertEquals(1, result.added());
        verify(engine).analyzeIncremental(eq(USER), eq(CONV), any(), any());
        verify(apply).apply(eq(USER), eq(CONV), any());
    }

    @Test
    void extractConversationSkipsWhenPausedAfterLlm() {
        when(engine.analyzeIncremental(anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            user.setMemoryEnabled(false);
            return emptyResult();
        });

        var result = service.extractConversation(USER, CONV);
        assertTrue(result.skipped());
        verify(apply, never()).apply(anyString(), anyString(), any());
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
        MemoryDocument m = memory("m", Category.INTEREST, "Loves fantasy", SourceType.EXPLICIT);
        m.setSourceConversationIds(new ArrayList<>(List.of(CONV)));
        m.setSourceMessageIds(new ArrayList<>(List.of("m1")));
        when(memories.findByUserIdAndSourceConversationIdsContaining(USER, CONV)).thenReturn(List.of(m));

        service.onConversationDeleted(USER, CONV);

        verify(memories).save(m);
        assertTrue(m.getSourceConversationIds().isEmpty());
        assertTrue(m.getSourceMessageIds().isEmpty());
    }
}
