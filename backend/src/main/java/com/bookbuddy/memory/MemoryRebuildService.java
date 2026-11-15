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
import com.bookbuddy.embedding.EmbeddingProvider;
import com.bookbuddy.jobs.JobHandler;
import com.bookbuddy.jobs.JobQueueDocument;
import com.bookbuddy.jobs.JobQueueDocument.Type;
import com.bookbuddy.memory.MemoryMerger.Action;
import com.bookbuddy.memory.MemoryMerger.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Re-derives the automatically learned memories from the user's (non-incognito) conversations.
 *
 * <p>Write-then-swap: every LLM call happens first and candidates are held in memory; only when all of them succeeded
 * are the new memories written, and only after that are the memories they replace removed. An LLM failure (or a
 * deferral because the budget is low) therefore changes nothing. Memories the user touched (verified, edited, pinned,
 * added by hand) are never replaced, and neither are memories whose source conversation was not re-read.
 */
@Service
public class MemoryRebuildService implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(MemoryRebuildService.class);

    static final int MAX_CONVERSATIONS = 50;
    static final int WINDOW = 30;

    private final UserRepository users;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final MemoryRepository memories;
    private final MemoryExtractor extractor;
    private final MemoryExtractionService extraction;
    private final MemoryMerger merger;
    private final MemoryWriter writer;
    private final EmbeddingProvider embeddings;
    private final LlmProvider llm;
    private final MemoryProperties props;

    public MemoryRebuildService(
            UserRepository users,
            ConversationRepository conversations,
            MessageRepository messages,
            MemoryRepository memories,
            MemoryExtractor extractor,
            MemoryExtractionService extraction,
            MemoryMerger merger,
            MemoryWriter writer,
            EmbeddingProvider embeddings,
            LlmProvider llm,
            MemoryProperties props) {
        this.users = users;
        this.conversations = conversations;
        this.messages = messages;
        this.memories = memories;
        this.extractor = extractor;
        this.extraction = extraction;
        this.merger = merger;
        this.writer = writer;
        this.embeddings = embeddings;
        this.llm = llm;
        this.props = props;
    }

    @Override
    public Type type() {
        return Type.MEMORY_REBUILD;
    }

    @Override
    public String handle(JobQueueDocument job) {
        return rebuild(job.getUserId());
    }

    public String rebuild(String userId) {
        UserDocument user = users.findById(userId).orElse(null);
        if (user == null) {
            throw new PermanentJobException("user gone");
        }
        if (!user.isMemoryEnabled()) {
            throw new PermanentJobException("memory is paused");
        }
        if (!llm.isConfigured()) {
            throw new PermanentJobException("language model unavailable");
        }

        // ---- 1. read everything, ask the model (no writes yet) ------------------------------------------------
        List<ConversationDocument> convs = new ArrayList<>();
        for (ConversationDocument c : conversations.findByUserIdOrderByUpdatedAtDescIdDesc(userId, Limit.of(MAX_CONVERSATIONS))) {
            if (!c.isIncognito()) {
                convs.add(c);
            }
        }
        Collections.reverse(convs); // oldest first, so a later correction can supersede an earlier statement

        record Found(String conversationId, MemoryCandidate candidate) {}
        List<Found> found = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        int calls = 0;
        outer:
        for (ConversationDocument c : convs) {
            List<MessageDocument> all = completeMessages(c);
            for (int from = 0; from < all.size(); from += WINDOW) {
                List<MessageDocument> window = all.subList(from, Math.min(all.size(), from + WINDOW));
                if (window.stream().noneMatch(m -> m.getRole() == Role.USER)) {
                    continue;
                }
                if (calls >= props.maxRebuildCalls()) {
                    break outer; // out of allowance: this conversation counts as not re-read
                }
                calls++;
                for (MemoryCandidate cand : extractor.extract(userId, window)) {
                    found.add(new Found(c.getId(), cand));
                }
            }
            processed.add(c.getId()); // reached only when every window of this conversation was read
        }

        // ---- 2. merge into what must be kept ------------------------------------------------------------------
        Instant now = Instant.now();
        List<MemoryDocument> existing = memories.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, MemoryDocument.Status.ACTIVE);
        List<MemoryDocument> replaceable = new ArrayList<>();
        List<MemoryDocument> working = new ArrayList<>();
        for (MemoryDocument m : existing) {
            boolean auto = !m.isUserVerified() && !m.isPinned();
            if (auto && m.getSourceConversationId() != null && processed.contains(m.getSourceConversationId())) {
                replaceable.add(m);
            } else {
                working.add(m);
            }
        }
        if (found.isEmpty() && !replaceable.isEmpty()) {
            // the model found nothing: that is far more likely a bad run than an empty person; keep what we have
            return "no changes: nothing new found, kept " + replaceable.size() + " existing";
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Found f : found) {
            float[] embedding = embeddings.embedPassage(f.candidate().content());
            outcomes.add(merger.merge(f.candidate(), userId, f.conversationId(), embedding, working, now));
        }

        // ---- 3. write the new generation, then swap out the old one ------------------------------------------
        writer.persist(outcomes);
        memories.deleteAll(replaceable);
        for (ConversationDocument c : convs) {
            if (processed.contains(c.getId())) {
                extraction.markExtracted(c, null);
            }
        }

        long added = outcomes.stream().filter(o -> o.action() == Action.ADD || o.action() == Action.SUPERSEDE).count();
        log.info("Memory rebuild finished: {} conversations, {} LLM calls", processed.size(), calls);
        return "rebuilt from " + processed.size() + " conversations: " + added + " memories, replaced "
                + replaceable.size();
    }

    private List<MessageDocument> completeMessages(ConversationDocument c) {
        List<MessageDocument> out = new ArrayList<>();
        for (MessageDocument m : messages.findByConversationIdAndUserIdOrderByCreatedAtAscIdAsc(c.getId(), c.getUserId())) {
            if (m.getStatus() == Status.COMPLETE && (m.getRole() == Role.USER || m.getRole() == Role.ASSISTANT)) {
                out.add(m);
            }
        }
        return out;
    }
}
