package com.bookbuddy.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookbuddy.account.AccountDtos.DeletionReport;
import com.bookbuddy.account.AccountDtos.ExportDocument;
import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.auth.UserRepository;
import com.bookbuddy.book.BookDocument;
import com.bookbuddy.book.BookRepository;
import com.bookbuddy.conversation.ConversationDocument;
import com.bookbuddy.conversation.ConversationRepository;
import com.bookbuddy.conversation.MessageDocument;
import com.bookbuddy.conversation.MessageDocument.Role;
import com.bookbuddy.conversation.MessageDocument.Status;
import com.bookbuddy.conversation.MessageRepository;
import com.bookbuddy.ingestion.IngestionJobRepository;
import com.bookbuddy.ingestion.IngestionJobService;
import com.bookbuddy.jobs.JobQueueService;
import com.bookbuddy.memory.MemoryDocument;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.SourceType;
import com.bookbuddy.memory.MemoryRepository;
import com.bookbuddy.quota.LlmUsageRepository;
import com.bookbuddy.vector.BookChunkRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountDataServiceTest {

    private static final String USER = "u1";

    private final UserRepository users = mock(UserRepository.class);
    private final BookRepository books = mock(BookRepository.class);
    private final BookChunkRepository chunks = mock(BookChunkRepository.class);
    private final IngestionJobRepository ingestionJobs = mock(IngestionJobRepository.class);
    private final IngestionJobService ingestion = mock(IngestionJobService.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final MemoryRepository memories = mock(MemoryRepository.class);
    private final JobQueueService jobs = mock(JobQueueService.class);
    private final LlmUsageRepository usage = mock(LlmUsageRepository.class);
    private AccountDataService service;

    @BeforeEach
    void setUp() {
        service = new AccountDataService(users, books, chunks, ingestionJobs, ingestion, conversations, messages, memories, jobs, usage);
    }

    @Test
    void exportContainsTheUsersDataButNeverTheirPasswordHashOrEmbeddings() throws Exception {
        UserDocument u = new UserDocument();
        u.setId(USER);
        u.setEmail("reader@example.com");
        u.setDisplayName("Reader");
        u.setPasswordHash("$2a$secret-hash");
        u.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(users.findById(USER)).thenReturn(Optional.of(u));

        ConversationDocument c = new ConversationDocument();
        c.setId("c1");
        c.setUserId(USER);
        c.setTitle("Mysteries");
        c.setCreatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        c.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        when(conversations.findByUserId(USER)).thenReturn(List.of(c));
        MessageDocument m = new MessageDocument();
        m.setId("m1");
        m.setConversationId("c1");
        m.setUserId(USER);
        m.setRole(Role.USER);
        m.setStatus(Status.COMPLETE);
        m.setContent("hello");
        m.setCreatedAt(Instant.parse("2026-01-02T00:00:01Z"));
        when(messages.findByConversationIdAndUserIdOrderByCreatedAtAscIdAsc("c1", USER)).thenReturn(List.of(m));

        MemoryDocument mem = new MemoryDocument();
        mem.setId("mem1");
        mem.setUserId(USER);
        mem.setCategory(Category.INTEREST);
        mem.setContent("Likes poetry");
        mem.setSourceType(SourceType.EXPLICIT);
        mem.setEmbedding(new ArrayList<>(List.of(0.1, 0.2)));
        mem.setCreatedAt(Instant.parse("2026-01-03T00:00:00Z"));
        mem.setUpdatedAt(Instant.parse("2026-01-03T00:00:00Z"));
        when(memories.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of(mem));

        ExportDocument doc = service.export(USER);
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(doc);

        assertEquals("reader@example.com", doc.account().email());
        assertEquals(1, doc.conversations().size());
        assertEquals(1, doc.conversations().get(0).messages().size());
        assertEquals(1, doc.memories().size());
        assertTrue(json.contains("Likes poetry"));
        assertTrue(json.contains("hello"));
        assertFalse(json.contains("secret-hash"), "password hashes are never exported");
        assertFalse(json.toLowerCase().contains("embedding"), "derived vectors are not part of the export");
    }

    @Test
    void deleteAllDataCascadesThroughEverythingTheUserOwns() {
        BookDocument b = new BookDocument();
        b.setId("b1");
        b.setUserId(USER);
        when(books.findByUserId(USER)).thenReturn(List.of(b));
        when(conversations.findByUserId(USER)).thenReturn(List.of(new ConversationDocument(), new ConversationDocument()));
        when(messages.countByUserId(USER)).thenReturn(7L);
        when(memories.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of(new MemoryDocument()));

        DeletionReport report = service.deleteAllData(USER);

        assertEquals(new DeletionReport(1, 2, 7, 1), report);
        var order = inOrder(jobs, ingestion, messages, conversations, memories, usage);
        order.verify(jobs).deleteForUser(USER);
        order.verify(ingestion).deleteBookCascade(USER, "b1");
        order.verify(messages).deleteByUserId(USER);
        order.verify(conversations).deleteByUserId(USER);
        order.verify(memories).deleteByUserId(USER);
        order.verify(usage).deleteByUserId(USER);
        verify(chunks).deleteByUserId(USER);
        verify(ingestionJobs).deleteByUserId(USER);
        verify(jobs, times(2)).deleteForUser(USER);
        verify(users, never()).delete(any());
        verify(users, never()).deleteById(anyString());
    }

    @Test
    void aFailingBookCascadeDoesNotStopTheRestOfTheDeletion() {
        BookDocument b = new BookDocument();
        b.setId("b1");
        b.setUserId(USER);
        when(books.findByUserId(USER)).thenReturn(List.of(b));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(ingestion).deleteBookCascade(USER, "b1");

        service.deleteAllData(USER);

        verify(chunks).deleteByUserId(USER);
        verify(messages).deleteByUserId(USER);
        verify(memories, times(2)).deleteByUserId(USER);
    }
}
