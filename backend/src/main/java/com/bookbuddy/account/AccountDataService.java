package com.bookbuddy.account;

import com.bookbuddy.account.AccountDtos.Account;
import com.bookbuddy.account.AccountDtos.DeletionReport;
import com.bookbuddy.account.AccountDtos.ExportConversation;
import com.bookbuddy.account.AccountDtos.ExportDocument;
import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.auth.UserRepository;
import com.bookbuddy.book.BookDocument;
import com.bookbuddy.book.BookDtos.BookResponse;
import com.bookbuddy.book.BookRepository;
import com.bookbuddy.common.ApiException;
import com.bookbuddy.conversation.ConversationDocument;
import com.bookbuddy.conversation.ConversationDtos.MessageResponse;
import com.bookbuddy.conversation.ConversationRepository;
import com.bookbuddy.conversation.MessageRepository;
import com.bookbuddy.ingestion.IngestionJobRepository;
import com.bookbuddy.ingestion.IngestionJobService;
import com.bookbuddy.jobs.JobQueueService;
import com.bookbuddy.memory.MemoryDtos.MemoryResponse;
import com.bookbuddy.memory.MemoryRepository;
import com.bookbuddy.quota.LlmUsageRepository;
import com.bookbuddy.vector.BookChunkRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Data rights: a full JSON export, and a hard, cascading delete of everything a user owns (PRD 12). */
@Service
public class AccountDataService {

    private static final Logger log = LoggerFactory.getLogger(AccountDataService.class);

    static final int EXPORT_VERSION = 1;

    private final UserRepository users;
    private final BookRepository books;
    private final BookChunkRepository chunks;
    private final IngestionJobRepository ingestionJobs;
    private final IngestionJobService ingestion;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final MemoryRepository memories;
    private final JobQueueService jobs;
    private final LlmUsageRepository usage;

    public AccountDataService(
            UserRepository users,
            BookRepository books,
            BookChunkRepository chunks,
            IngestionJobRepository ingestionJobs,
            IngestionJobService ingestion,
            ConversationRepository conversations,
            MessageRepository messages,
            MemoryRepository memories,
            JobQueueService jobs,
            LlmUsageRepository usage) {
        this.users = users;
        this.books = books;
        this.chunks = chunks;
        this.ingestionJobs = ingestionJobs;
        this.ingestion = ingestion;
        this.conversations = conversations;
        this.messages = messages;
        this.memories = memories;
        this.jobs = jobs;
        this.usage = usage;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Export
    // ---------------------------------------------------------------------------------------------------------

    public ExportDocument export(String userId) {
        UserDocument u = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("User no longer exists"));
        Instant now = Instant.now();

        List<BookResponse> bookList = books.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(BookResponse::from)
                .toList();

        List<ExportConversation> conversationList = new ArrayList<>();
        for (ConversationDocument c : conversations.findByUserId(userId)) {
            List<MessageResponse> msgs = messages
                    .findByConversationIdAndUserIdOrderByCreatedAtAscIdAsc(c.getId(), userId).stream()
                    .map(MessageResponse::from)
                    .toList();
            conversationList.add(new ExportConversation(
                    c.getId(), c.getTitle(), c.isIncognito(), c.getBookScope(), c.getSummary(),
                    c.getCreatedAt(), c.getUpdatedAt(), msgs));
        }

        List<MemoryResponse> memoryList = memories.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(m -> MemoryResponse.from(m, now))
                .toList();

        Account account = new Account(
                u.getId(), u.getEmail(), u.getDisplayName(), u.isMemoryEnabled(), u.getDefaultLanguage(),
                u.getConsent(), u.getCrisisContacts(), u.getCreatedAt());
        return new ExportDocument(EXPORT_VERSION, now, account, bookList, conversationList, memoryList);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Delete everything
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Hard-deletes the user's books (with chunks, embeddings, ingestion jobs and pending uploads), conversations,
     * messages (and so summaries and message citations), memories, queued background jobs and usage records. The
     * account itself and its settings stay. Idempotent.
     */
    public DeletionReport deleteAllData(String userId) {
        // stop queued background work first so nothing is re-created while we delete
        jobs.deleteForUser(userId);

        List<BookDocument> bookDocs = books.findByUserId(userId);
        for (BookDocument b : bookDocs) {
            try {
                ingestion.deleteBookCascade(userId, b.getId());
            } catch (RuntimeException e) {
                log.warn("Book cascade failed for one book ({}); falling back to bulk delete", e.getClass().getSimpleName());
            }
        }
        books.deleteAll(books.findByUserId(userId));
        chunks.deleteByUserId(userId);
        ingestionJobs.deleteByUserId(userId);

        long conversationCount = conversations.findByUserId(userId).size();
        long messageCount = messages.countByUserId(userId);
        messages.deleteByUserId(userId);
        conversations.deleteByUserId(userId);

        long memoryCount = memories.findByUserIdOrderByUpdatedAtDesc(userId).size();
        memories.deleteByUserId(userId);
        usage.deleteByUserId(userId);

        // a background job that was already running may have written after the first sweep
        jobs.deleteForUser(userId);
        memories.deleteByUserId(userId);

        return new DeletionReport(bookDocs.size(), conversationCount, messageCount, memoryCount);
    }
}
