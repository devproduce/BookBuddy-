package com.bookbuddy.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookbuddy.ai.LlmException;
import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.conversation.MessageDocument.Role;
import com.bookbuddy.conversation.MessageDocument.Status;
import com.bookbuddy.document.TextCleaningService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

class RollingSummaryServiceTest {

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final LlmProvider llm = mock(LlmProvider.class);
    private RollingSummaryService service;
    private ConversationDocument conversation;
    private final List<MessageDocument> newestFirst = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new RollingSummaryService(conversations, messages, llm, new TextCleaningService());
        service.setThresholdTokens(200);
        conversation = new ConversationDocument();
        conversation.setId("c1");
        conversation.setUserId("u1");
        when(conversations.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdAndUserIdOrderByCreatedAtDescIdDesc(anyString(), anyString(), any(Limit.class)))
                .thenAnswer(inv -> List.copyOf(newestFirst));
    }

    /** Appends a message (chronologically last). */
    private void add(int n, Role role, String text, Status status) {
        MessageDocument m = new MessageDocument();
        m.setId(String.format("m%03d", n));
        m.setConversationId("c1");
        m.setUserId("u1");
        m.setRole(role);
        m.setContent(text);
        m.setStatus(status);
        m.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(n));
        newestFirst.add(0, m);
    }

    private void fill(int count) {
        String filler = "This is a fairly long sentence about lighthouses and the keeper who lived there. ".repeat(4);
        for (int i = 1; i <= count; i++) {
            add(i, i % 2 == 1 ? Role.USER : Role.ASSISTANT, filler + i, Status.COMPLETE);
        }
    }

    @Test
    void doesNothingBelowTheThreshold() {
        service.setThresholdTokens(100_000);
        fill(12);
        assertFalse(service.summarizeIfNeeded("u1", "c1"));
        verify(conversations, never()).save(any(ConversationDocument.class));
    }

    @Test
    void foldsOlderTurnsKeepingRecentOnesAndAdvancesTheMarker() {
        fill(12);
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("The reader asked about a lighthouse keeper.");

        assertTrue(service.summarizeIfNeeded("u1", "c1"));

        assertEquals("The reader asked about a lighthouse keeper.", conversation.getSummary());
        // 12 messages, newest 6 kept verbatim => the 6th message is the last one folded in
        assertEquals("m006", conversation.getLastSummarizedMessageId());
        verify(conversations).save(conversation);
    }

    @Test
    void secondRunOnlyConsidersMessagesAfterTheMarker() {
        fill(12);
        conversation.setSummary("old summary");
        conversation.setLastSummarizedMessageId("m006");
        when(llm.isConfigured()).thenReturn(false);

        // only m007..m012 remain un-summarised and that is exactly KEEP_RECENT => nothing to fold
        assertFalse(service.summarizeIfNeeded("u1", "c1"));
        assertEquals("old summary", conversation.getSummary());

        fill13to18();
        assertTrue(service.summarizeIfNeeded("u1", "c1"));
        assertEquals("m012", conversation.getLastSummarizedMessageId());
        assertTrue(conversation.getSummary().startsWith("old summary"), "extractive fallback appends to the old one");
    }

    private void fill13to18() {
        String filler = "Another long sentence about the sea and the storm that came in at night. ".repeat(4);
        for (int i = 13; i <= 18; i++) {
            add(i, i % 2 == 1 ? Role.USER : Role.ASSISTANT, filler + i, Status.COMPLETE);
        }
    }

    @Test
    void extractiveFallbackIsUsedWithoutAKeyOrWhenTheLlmFails() {
        fill(12);
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenThrow(new LlmException("down"));

        assertTrue(service.summarizeIfNeeded("u1", "c1"));

        assertNotNull(conversation.getSummary());
        assertTrue(conversation.getSummary().contains("User: This is a fairly long sentence"), conversation.getSummary());
        assertEquals("m006", conversation.getLastSummarizedMessageId());
    }

    @Test
    void failedAndInterruptedMessagesDoNotCount() {
        for (int i = 1; i <= 12; i++) {
            add(i, i % 2 == 1 ? Role.USER : Role.ASSISTANT, "x ".repeat(2000), Status.FAILED);
        }
        assertFalse(service.summarizeIfNeeded("u1", "c1"));
        assertNull(conversation.getSummary());
    }

    @Test
    void unknownConversationIsIgnored() {
        assertFalse(service.summarizeIfNeeded("u1", "missing"));
    }
}