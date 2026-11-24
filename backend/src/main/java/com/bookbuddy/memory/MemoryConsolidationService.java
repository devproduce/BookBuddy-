package com.bookbuddy.memory;

import com.bookbuddy.embedding.EmbeddingProvider;
import com.bookbuddy.embedding.VectorMath;
import com.bookbuddy.jobs.JobHandler;
import com.bookbuddy.jobs.JobQueueDocument;
import com.bookbuddy.jobs.JobQueueDocument.Type;
import com.bookbuddy.memory.MemoryDocument.SourceType;
import com.bookbuddy.memory.MemoryDocument.Status;
import com.bookbuddy.quota.LlmBudgetService;
import com.bookbuddy.quota.LlmBudgetService.Priority;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Lowest-priority housekeeping: archives near-identical active memories (keeping the more trustworthy one) so the
 * prompt budget is not wasted on repeats. It uses embeddings only and never rewrites or deletes content; an archived
 * memory points at its survivor through {@code supersededBy}. Memories the user pinned or verified are never archived.
 */
@Service
public class MemoryConsolidationService implements JobHandler {

    private static final double SEMANTIC_NEAR_DUPLICATE = 0.95;
    private static final double LEXICAL_NEAR_DUPLICATE = 0.85;

    private final MemoryRepository memories;
    private final EmbeddingProvider embeddings;
    private final LlmBudgetService budget;

    public MemoryConsolidationService(MemoryRepository memories, EmbeddingProvider embeddings, LlmBudgetService budget) {
        this.memories = memories;
        this.embeddings = embeddings;
        this.budget = budget;
    }

    @Override
    public Type type() {
        return Type.MEMORY_CONSOLIDATION;
    }

    @Override
    public String handle(JobQueueDocument job) {
        if (!budget.allows(Priority.CONSOLIDATION)) {
            throw new BudgetDeferredException("LLM budget low");
        }
        return "archived " + consolidate(job.getUserId()) + " duplicates";
    }

    /** @return how many memories were archived */
    public int consolidate(String userId) {
        Instant now = Instant.now();
        List<MemoryDocument> usable = new ArrayList<>();
        for (MemoryDocument m : memories.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, Status.ACTIVE)) {
            if (MemoryPolicy.isUsable(m, now)) {
                usable.add(m);
            }
        }
        double bar = embeddings.isSemantic() ? SEMANTIC_NEAR_DUPLICATE : LEXICAL_NEAR_DUPLICATE;
        float[][] vectors = new float[usable.size()][];
        for (int i = 0; i < usable.size(); i++) {
            MemoryDocument m = usable.get(i);
            vectors[i] = embeddings.modelName().equals(m.getEmbeddingModel()) && m.getEmbedding() != null
                            && !m.getEmbedding().isEmpty()
                    ? VectorMath.toArray(m.getEmbedding())
                    : embeddings.embedPassage(m.getContent());
        }

        Set<String> archived = new HashSet<>();
        List<MemoryDocument> toSave = new ArrayList<>();
        for (int i = 0; i < usable.size(); i++) {
            MemoryDocument a = usable.get(i);
            if (archived.contains(a.getId())) {
                continue;
            }
            for (int j = i + 1; j < usable.size(); j++) {
                MemoryDocument b = usable.get(j);
                if (archived.contains(b.getId()) || a.getCategory() != b.getCategory()) {
                    continue;
                }
                if (VectorMath.cosine(vectors[i], vectors[j]) < bar) {
                    continue;
                }
                MemoryDocument keep = better(a, b);
                MemoryDocument drop = keep == a ? b : a;
                if (drop.isPinned() || drop.isUserConfirmed() || drop.isStated()) {
                    continue; // never archive what the user shaped
                }
                drop.setStatus(Status.ARCHIVED);
                drop.setSupersededBy(keep.getId());
                drop.setUpdatedAt(now);
                archived.add(drop.getId());
                toSave.add(drop);
                if (drop == a) {
                    break;
                }
            }
        }
        for (MemoryDocument m : toSave) {
            memories.save(m);
        }
        return toSave.size();
    }

    /** Stated beats inferred, verified/pinned beats not, then higher importance, then the more recently touched. */
    static MemoryDocument better(MemoryDocument a, MemoryDocument b) {
        int sa = score(a);
        int sb = score(b);
        if (sa != sb) {
            return sa > sb ? a : b;
        }
        if (a.getImportance() != b.getImportance()) {
            return a.getImportance() > b.getImportance() ? a : b;
        }
        Instant ua = a.getUpdatedAt() == null ? Instant.EPOCH : a.getUpdatedAt();
        Instant ub = b.getUpdatedAt() == null ? Instant.EPOCH : b.getUpdatedAt();
        return ua.isBefore(ub) ? b : a;
    }

    private static int score(MemoryDocument m) {
        return (m.isPinned() ? 4 : 0)
                + (m.isUserConfirmed() ? 2 : 0)
                + (m.getSourceType() == SourceType.EXPLICIT ? 1 : 0);
    }
}
