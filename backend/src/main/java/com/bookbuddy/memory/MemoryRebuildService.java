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
import com.bookbuddy.memory.MemoryDocument.SourceType;
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
 * Re-derives automatically learned memories from the user's (non-incognito) conversations via HISTORICAL_ANALYSIS.
 *
 * <p>Pinned / EXPLICIT / USER_CONFIRMED memories are preserved. Incompatible auto memories from processed
 * conversations are replaced after a successful full run (write-then-swap via {@link MemoryApplyService}).
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
    private final MemoryEngineClient engine;
    private final MemoryApplyService apply;
    private final MemoryExtractionService extraction;
    private final LlmProvider llm;
    private final MemoryProperties props;

    public MemoryRebuildService(
            UserRepository users,
            ConversationRepository conversations,
            MessageRepository messages,
            MemoryRepository memories,
            MemoryEngineClient engine,
            MemoryApplyService apply,
            MemoryExtractionService extraction,
            LlmProvider llm,
            MemoryProperties props) {
        this.users = users;
        this.conversations = conversations;
        this.messages = messages;
        this.memories = memories;
        this.engine = engine;
        this.apply = apply;
        this.extraction = extraction;
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

        List<ConversationDocument> convs = new ArrayList<>();
        for (ConversationDocument c :
                conversations.findByUserIdOrderByUpdatedAtDescIdDesc(userId, Limit.of(MAX_CONVERSATIONS))) {
            if (!c.isIncognito()) {
                convs.add(c);
            }
        }
        Collections.reverse(convs);

        // Drop replaceable auto memories from old schema / prior auto extracts before historical rebuild
        List<MemoryDocument> existing =
                memories.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, MemoryDocument.Status.ACTIVE);
        List<MemoryDocument> keep = new ArrayList<>();
        List<MemoryDocument> replaceable = new ArrayList<>();
        for (MemoryDocument m : existing) {
            boolean protectedMem = m.isPinned()
                    || m.isUserConfirmed()
                    || m.getSourceType() == SourceType.EXPLICIT;
            if (protectedMem) {
                keep.add(m);
            } else {
                replaceable.add(m);
            }
        }

        List<MemoryDocument> draft = new ArrayList<>(keep);
        Set<String> processed = new HashSet<>();
        int calls = 0;
        int batches = 0;
        MemoryApplyService.ApplyResult last = null;

        outer:
        for (int ci = 0; ci < convs.size(); ci++) {
            ConversationDocument c = convs.get(ci);
            List<MessageDocument> all = completeMessages(c);
            for (int from = 0; from < all.size(); from += WINDOW) {
                List<MessageDocument> window = all.subList(from, Math.min(all.size(), from + WINDOW));
                if (window.stream().noneMatch(m -> m.getRole() == Role.USER)) {
                    continue;
                }
                if (calls >= props.maxRebuildCalls()) {
                    break outer;
                }
                calls++;
                batches++;
                boolean finalize = ci == convs.size() - 1 && from + WINDOW >= all.size();
                MemoryEngineResult result = engine.analyzeHistorical(
                        userId, window, Set.of(c.getId()), draft, finalize);
                last = apply.apply(userId, c.getId(), result);
                draft = memories.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, MemoryDocument.Status.ACTIVE);
            }
            processed.add(c.getId());
        }

        if (batches == 0) {
            return "no changes: nothing to analyze";
        }

        // After successful historical runs, remove leftover replaceable memories that were never touched
        Set<String> draftIds = new HashSet<>();
        for (MemoryDocument m : draft) {
            draftIds.add(m.getId());
        }
        List<MemoryDocument> stillReplaceable = new ArrayList<>();
        for (MemoryDocument m : replaceable) {
            if (!draftIds.contains(m.getId())) {
                stillReplaceable.add(m);
            }
        }
        if (!stillReplaceable.isEmpty()) {
            memories.deleteAll(stillReplaceable);
        }

        for (ConversationDocument c : convs) {
            if (processed.contains(c.getId())) {
                extraction.markExtracted(c, null);
            }
        }

        // Final consolidation pass for profile polish when we have many memories
        if (draft.size() > 20 && calls < props.maxRebuildCalls()) {
            MemoryEngineResult cons = engine.consolidate(userId, draft);
            apply.apply(userId, null, cons);
        }

        log.info("Memory rebuild finished: {} conversations, {} LLM calls", processed.size(), calls);
        String applied = last == null ? "n/a" : last.summary();
        return "rebuilt from " + processed.size() + " conversations (" + calls + " calls): " + applied
                + ", removed " + stillReplaceable.size() + " stale auto memories";
    }

    private List<MessageDocument> completeMessages(ConversationDocument c) {
        List<MessageDocument> out = new ArrayList<>();
        for (MessageDocument m :
                messages.findByConversationIdAndUserIdOrderByCreatedAtAscIdAsc(c.getId(), c.getUserId())) {
            if (m.getStatus() == Status.COMPLETE && (m.getRole() == Role.USER || m.getRole() == Role.ASSISTANT)) {
                out.add(m);
            }
        }
        return out;
    }
}
