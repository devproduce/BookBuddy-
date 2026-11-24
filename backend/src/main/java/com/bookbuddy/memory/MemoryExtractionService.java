package com.bookbuddy.memory;

import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.auth.UserRepository;
import com.bookbuddy.conversation.ConversationDocument;
import com.bookbuddy.conversation.ConversationRepository;
import com.bookbuddy.conversation.MessageDocument;
import com.bookbuddy.conversation.MessageDocument.Role;
import com.bookbuddy.conversation.MessageDocument.Status;
import com.bookbuddy.conversation.MessageRepository;
import com.bookbuddy.jobs.JobHandler;
import com.bookbuddy.jobs.JobQueueDocument;
import com.bookbuddy.jobs.JobQueueDocument.Type;
import com.bookbuddy.jobs.JobQueueService;
import com.bookbuddy.memory.MemoryMerger.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Turns finished conversations into memories, in the background and never on the reply path.
 *
 * <p><b>Trigger policy</b> (protects the LLM quota): a conversation is queued for extraction when
 * {@code extractEveryMessages} new messages have accumulated, or when it has been idle for {@code idleMinutes}.
 * Incognito conversations and users with memory paused are never extracted. Work is queued durably
 * ({@code jobs_queue}) and runs through {@link com.bookbuddy.jobs.JobWorker}, which defers it while the budget is low.
 *
 * <p><b>Safety</b>: an LLM/validation failure aborts the run before anything is written; writes are write-then-swap
 * ({@link MemoryWriter}); nothing is deleted.
 */
@Service
public class MemoryExtractionService implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionService.class);

    static final int WINDOW_MAX_MESSAGES = 30;
    static final Duration IDLE_LOOKBACK = Duration.ofHours(24);
    static final int SCAN_LIMIT = 200;

    /** @param skipped true when nothing was attempted (see {@code reason}) */
    public record Result(
            boolean skipped, String reason, int added, int updated, int merged, int superseded, int ignored) {

        static Result skipped(String reason) {
            return new Result(true, reason, 0, 0, 0, 0, 0);
        }

        String summary() {
            return skipped
                    ? "skipped: " + reason
                    : "added=" + added + " updated=" + updated + " merged=" + merged + " superseded=" + superseded
                            + " ignored=" + ignored;
        }
    }

    private final UserRepository users;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final MemoryRepository memories;
    private final MemoryExtractor extractor;
    private final MemoryMerger merger;
    private final MemoryWriter writer;
    private final com.bookbuddy.embedding.EmbeddingProvider embeddings;
    private final JobQueueService jobs;
    private final LlmProvider llm;
    private final MemoryProperties props;
    private final MongoTemplate mongo;
    private final boolean jobsEnabled;

    public MemoryExtractionService(
            UserRepository users,
            ConversationRepository conversations,
            MessageRepository messages,
            MemoryRepository memories,
            MemoryExtractor extractor,
            MemoryMerger merger,
            MemoryWriter writer,
            com.bookbuddy.embedding.EmbeddingProvider embeddings,
            JobQueueService jobs,
            LlmProvider llm,
            MemoryProperties props,
            MongoTemplate mongo,
            @Value("${bookbuddy.jobs.enabled:true}") boolean jobsEnabled) {
        this.users = users;
        this.conversations = conversations;
        this.messages = messages;
        this.memories = memories;
        this.extractor = extractor;
        this.merger = merger;
        this.writer = writer;
        this.embeddings = embeddings;
        this.jobs = jobs;
        this.llm = llm;
        this.props = props;
        this.mongo = mongo;
        this.jobsEnabled = jobsEnabled;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Triggers
    // ---------------------------------------------------------------------------------------------------------

    /** Called after every completed chat turn (cheap: at most one queue insert). Never throws. */
    public void onTurnCompleted(UserDocument user, ConversationDocument conversation) {
        try {
            if (user == null || conversation == null || conversation.isIncognito() || !user.isMemoryEnabled()
                    || !llm.isConfigured()) {
                return;
            }
            int pending = conversation.getMessageCount() - conversation.getLastExtractedMessageCount();
            if (pending >= props.extractEveryMessages()) {
                enqueue(conversation);
            }
        } catch (RuntimeException e) {
            log.debug("Could not queue memory extraction: {}", e.getClass().getSimpleName());
        }
    }

    /** Queues extraction for conversations that went quiet with unprocessed messages. */
    @Scheduled(initialDelayString = "${bookbuddy.jobs.initial-delay-ms:15000}",
            fixedDelayString = "${bookbuddy.memory.scan-ms:60000}")
    void scheduledIdleScan() {
        if (!jobsEnabled) {
            return;
        }
        try {
            scanIdle(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Idle memory scan failed: {}", e.getClass().getSimpleName());
        }
    }

    /** @return how many conversations were queued */
    int scanIdle(Instant now) {
        if (!llm.isConfigured()) {
            return 0;
        }
        List<ConversationDocument> quiet = conversations.findByIncognitoFalseAndUpdatedAtBetween(
                now.minus(IDLE_LOOKBACK), now.minus(Duration.ofMinutes(props.idleMinutes())), Limit.of(SCAN_LIMIT));
        Map<String, Boolean> memoryOn = new HashMap<>();
        int queued = 0;
        for (ConversationDocument c : quiet) {
            if (c.getMessageCount() <= c.getLastExtractedMessageCount()) {
                continue;
            }
            boolean on = memoryOn.computeIfAbsent(
                    c.getUserId(), id -> users.findById(id).map(UserDocument::isMemoryEnabled).orElse(false));
            if (!on) {
                continue;
            }
            enqueue(c);
            queued++;
        }
        return queued;
    }

    private void enqueue(ConversationDocument c) {
        // keyed by message count: new messages mean new work, but the same work is never queued (or retried) twice
        jobs.enqueueOnce(
                Type.MEMORY_EXTRACTION,
                c.getUserId(),
                Map.of("conversationId", c.getId()),
                "memory-extract:" + c.getId() + ":" + c.getMessageCount());
    }

    /** When a conversation is deleted, memories it produced keep existing but forget where they came from. */
    public void onConversationDeleted(String userId, String conversationId) {
        try {
            for (MemoryDocument m : memories.findByUserIdAndSourceConversationIdsContaining(userId, conversationId)) {
                List<String> convIds = m.getSourceConversationIds() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(m.getSourceConversationIds());
                convIds.remove(conversationId);
                m.setSourceConversationIds(convIds);
                m.setSourceMessageIds(new ArrayList<>());
                memories.save(m);
            }
        } catch (RuntimeException e) {
            log.debug("Could not detach memory sources: {}", e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Job handler
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public Type type() {
        return Type.MEMORY_EXTRACTION;
    }

    @Override
    public String handle(JobQueueDocument job) {
        String conversationId = job.getPayload() == null ? null : job.getPayload().get("conversationId");
        if (conversationId == null) {
            throw new PermanentJobException("conversationId missing");
        }
        return extractConversation(job.getUserId(), conversationId).summary();
    }

    /**
     * Mines the new messages of one conversation.
     *
     * @throws BudgetDeferredException the budget is low; try again later
     * @throws com.bookbuddy.ai.LlmException the provider failed; nothing was written
     */
    public Result extractConversation(String userId, String conversationId) {
        UserDocument user = users.findById(userId).orElse(null);
        ConversationDocument conversation = conversations.findByIdAndUserId(conversationId, userId).orElse(null);
        if (user == null || conversation == null) {
            return Result.skipped("conversation gone");
        }
        if (conversation.isIncognito()) {
            markExtracted(conversation, null);
            return Result.skipped("incognito");
        }
        if (!user.isMemoryEnabled()) {
            markExtracted(conversation, null); // paused: do not learn retroactively from this period
            return Result.skipped("memory paused");
        }
        if (!llm.isConfigured()) {
            return Result.skipped("language model unavailable");
        }

        List<MessageDocument> window = newMessages(conversation);
        if (window.stream().noneMatch(m -> m.getRole() == Role.USER)) {
            markExtracted(conversation, window);
            return Result.skipped("nothing new");
        }

        // LLM first, writes later: a failure here leaves every memory exactly as it was
        List<MemoryCandidate> candidates = extractor.extract(userId, window);

        // the world may have changed while the model was thinking
        user = users.findById(userId).orElse(null);
        ConversationDocument fresh = conversations.findByIdAndUserId(conversationId, userId).orElse(null);
        if (user == null || fresh == null || fresh.isIncognito() || !user.isMemoryEnabled()) {
            return Result.skipped("conversation changed");
        }

        Instant now = Instant.now();
        List<MemoryDocument> working = new ArrayList<>(memories.findByUserIdAndStatusOrderByUpdatedAtDesc(
                userId, MemoryDocument.Status.ACTIVE));
        List<Outcome> outcomes = new ArrayList<>();
        for (MemoryCandidate c : candidates) {
            float[] embedding = embeddings.embedPassage(c.content());
            outcomes.add(merger.merge(c, userId, conversationId, embedding, working, now));
        }
        writer.persist(outcomes);
        markExtracted(fresh, window);

        int[] counts = new int[MemoryMerger.Action.values().length];
        for (Outcome o : outcomes) {
            counts[o.action().ordinal()]++;
        }
        if (working.size() > props.consolidateAbove()) {
            jobs.enqueue(Type.MEMORY_CONSOLIDATION, userId, Map.of(), "memory-consolidate:" + userId);
        }
        return new Result(false, null,
                counts[MemoryMerger.Action.ADD.ordinal()],
                counts[MemoryMerger.Action.UPDATE.ordinal()],
                counts[MemoryMerger.Action.MERGE.ordinal()],
                counts[MemoryMerger.Action.SUPERSEDE.ordinal()],
                counts[MemoryMerger.Action.IGNORE.ordinal()]);
    }

    /** Complete USER/ASSISTANT messages newer than the last extraction, oldest first, at most 30. */
    List<MessageDocument> newMessages(ConversationDocument conversation) {
        List<MessageDocument> newestFirst = messages.findByConversationIdAndUserIdOrderByCreatedAtDescIdDesc(
                conversation.getId(), conversation.getUserId(), Limit.of(WINDOW_MAX_MESSAGES));
        List<MessageDocument> window = new ArrayList<>(newestFirst);
        Collections.reverse(window);
        Instant since = conversation.getLastMemoryExtractedAt();
        List<MessageDocument> out = new ArrayList<>();
        for (MessageDocument m : window) {
            if (m.getStatus() != Status.COMPLETE || (m.getRole() != Role.USER && m.getRole() != Role.ASSISTANT)) {
                continue;
            }
            if (since != null && m.getCreatedAt() != null && !m.getCreatedAt().isAfter(since)) {
                continue;
            }
            out.add(m);
        }
        return out;
    }

    /** Partial update so a concurrent chat turn or summary is never overwritten. */
    void markExtracted(ConversationDocument conversation, List<MessageDocument> window) {
        Instant upTo = window == null
                ? Instant.now()
                : window.stream().map(MessageDocument::getCreatedAt).filter(java.util.Objects::nonNull)
                        .max(Instant::compareTo).orElse(Instant.now());
        try {
            mongo.updateFirst(
                    Query.query(Criteria.where("_id").is(conversation.getId())),
                    new Update()
                            .set("lastMemoryExtractedAt", upTo)
                            .set("lastExtractedMessageCount", conversation.getMessageCount()),
                    ConversationDocument.class);
        } catch (RuntimeException e) {
            log.debug("Could not mark conversation as extracted: {}", e.getClass().getSimpleName());
        }
    }
}
