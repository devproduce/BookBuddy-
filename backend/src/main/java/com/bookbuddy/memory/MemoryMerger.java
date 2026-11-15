package com.bookbuddy.memory;

import com.bookbuddy.embedding.EmbeddingProvider;
import com.bookbuddy.embedding.VectorMath;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Classification;
import com.bookbuddy.memory.MemoryDocument.Sensitivity;
import com.bookbuddy.memory.MemoryDocument.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

/**
 * Decides what to do with a screened candidate given what is already remembered (ADD / UPDATE / MERGE / SUPERSEDE /
 * IGNORE) and applies the decision <em>in memory</em>. Nothing here touches the database: callers persist the
 * {@link Outcome}s write-then-swap (new documents first, modifications and archivings after), so a failure half-way
 * never loses a memory.
 *
 * <p>Rules that matter for trust:
 * <ul>
 *   <li>An inferred candidate never blends into, overrides or "confirms" something the user stated or verified; it
 *       is ignored (duplicate) or kept as its own INFERRED memory.
 *   <li>An explicit statement may replace an unconfirmed inference.
 *   <li>Merged or updated INFERRED memories stay INFERRED with confidence capped at 0.7.
 * </ul>
 */
@Component
public class MemoryMerger {

    public enum Action {
        ADD,
        UPDATE,
        MERGE,
        SUPERSEDE,
        IGNORE
    }

    /** Cosine cut-offs. Hash (lexical) embeddings score paraphrases lower than semantic ones, so they get lower bars. */
    public record Thresholds(double duplicate, double merge) {}

    public record Match(MemoryDocument memory, double similarity) {}

    public record Decision(Action action, MemoryDocument target, double similarity) {}

    /**
     * Result of applying one candidate. {@code created} is new (not yet persisted), {@code changed} was modified in
     * place (not yet persisted) and {@code superseded} was archived because {@code created} replaces it.
     */
    public record Outcome(
            Action action, MemoryDocument created, MemoryDocument changed, MemoryDocument superseded,
            double similarity) {}

    private static final double LEXICAL_DUPLICATE = 0.75;
    private static final double LEXICAL_MERGE = 0.5;

    private final EmbeddingProvider embeddings;
    private final MemoryProperties props;

    public MemoryMerger(EmbeddingProvider embeddings, MemoryProperties props) {
        this.embeddings = embeddings;
        this.props = props;
    }

    public Thresholds thresholds() {
        return embeddings.isSemantic()
                ? new Thresholds(props.duplicateSimilarity(), props.mergeSimilarity())
                : new Thresholds(LEXICAL_DUPLICATE, LEXICAL_MERGE);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Matching and deciding
    // ---------------------------------------------------------------------------------------------------------

    /** The most similar usable memory, or null when none is usable. */
    public Match bestMatch(float[] embedding, Collection<MemoryDocument> active, Instant now) {
        Match best = null;
        for (MemoryDocument m : active) {
            if (!MemoryPolicy.isUsable(m, now)) {
                continue;
            }
            double s = VectorMath.cosine(embedding, embeddingOf(m));
            if (best == null || s > best.similarity()) {
                best = new Match(m, s);
            }
        }
        return best;
    }

    /** The stored vector if it came from the current model, else a fresh one (never mixes embedding spaces). */
    public List<Double> embeddingOf(MemoryDocument m) {
        if (m.getEmbedding() != null && !m.getEmbedding().isEmpty()
                && embeddings.modelName().equals(m.getEmbeddingModel())) {
            return m.getEmbedding();
        }
        return VectorMath.toList(embeddings.embedPassage(m.getContent()));
    }

    public Decision decide(MemoryCandidate c, Match best) {
        Thresholds t = thresholds();
        if (best == null || best.similarity() < t.merge()) {
            return new Decision(Action.ADD, null, best == null ? 0 : best.similarity());
        }
        MemoryDocument e = best.memory();
        double s = best.similarity();
        boolean candidateExplicit = c.classification() == Classification.EXPLICIT;

        if (e.getCategory() != c.category()) {
            // a different facet of the person: only a near-identical statement counts as already known
            return new Decision(s >= t.duplicate() ? Action.IGNORE : Action.ADD, e, s);
        }
        if (candidateExplicit && !e.isUserVerified() && e.getClassification() == Classification.INFERRED) {
            return new Decision(Action.UPDATE, e, s); // the user said it themselves: replaces the guess
        }
        if (!candidateExplicit && e.isStated()) {
            // an inference never touches what the user stated or confirmed
            return new Decision(s >= t.duplicate() ? Action.IGNORE : Action.ADD, e, s);
        }
        if (c.replacesPrevious()) {
            return new Decision(Action.SUPERSEDE, e, s); // before the duplicate test: a negation embeds close to its original
        }
        if (s >= t.duplicate()) {
            return new Decision(Action.IGNORE, e, s);
        }
        String nc = MemoryPolicy.normalize(c.content());
        String ne = MemoryPolicy.normalize(e.getContent());
        if (ne.contains(nc)) {
            return new Decision(Action.IGNORE, e, s);
        }
        if (nc.contains(ne)) {
            return new Decision(Action.UPDATE, e, s);
        }
        return new Decision(Action.MERGE, e, s);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Applying
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Decides and applies one candidate against {@code working} (the user's active memories, kept up to date so later
     * candidates see earlier ones).
     */
    public Outcome merge(
            MemoryCandidate c, String userId, String conversationId, float[] embedding,
            List<MemoryDocument> working, Instant now) {
        Decision d = decide(c, bestMatch(embedding, working, now));
        switch (d.action()) {
            case IGNORE:
                return new Outcome(Action.IGNORE, null, null, null, d.similarity());
            case UPDATE:
                update(d.target(), c, conversationId, c.content(), now);
                return new Outcome(Action.UPDATE, null, d.target(), null, d.similarity());
            case MERGE:
                update(d.target(), c, conversationId, combine(d.target().getContent(), c.content()), now);
                return new Outcome(Action.MERGE, null, d.target(), null, d.similarity());
            case SUPERSEDE: {
                MemoryDocument fresh = create(c, userId, conversationId, embedding, now);
                MemoryDocument old = d.target();
                old.setStatus(Status.ARCHIVED);
                old.setSupersededBy(fresh.getId());
                old.setUpdatedAt(now);
                working.remove(old);
                working.add(fresh);
                return new Outcome(Action.SUPERSEDE, fresh, null, old, d.similarity());
            }
            default: {
                MemoryDocument fresh = create(c, userId, conversationId, embedding, now);
                working.add(fresh);
                return new Outcome(Action.ADD, fresh, null, null, d.similarity());
            }
        }
    }

    public MemoryDocument create(
            MemoryCandidate c, String userId, String conversationId, float[] embedding, Instant now) {
        MemoryDocument m = new MemoryDocument();
        m.setId(new ObjectId().toHexString()); // known up front so SUPERSEDE can link old -> new
        m.setUserId(userId);
        m.setCategory(c.category());
        m.setContent(c.content());
        m.setSourceConversationId(conversationId);
        m.setSourceMessageIds(new ArrayList<>(c.sourceMessageIds()));
        m.setClassification(c.classification());
        m.setConfidence(cap(c.classification(), false, c.confidence()));
        m.setImportance(c.importance());
        m.setEmbedding(VectorMath.toList(embedding));
        m.setEmbeddingModel(embeddings.modelName());
        m.setSensitivity(c.sensitive() ? Sensitivity.SENSITIVE : Sensitivity.NORMAL);
        m.setStatus(Status.ACTIVE);
        m.setExpiresAt(MemoryPolicy.defaultExpiry(c.category(), false, now, props.emotionalContextDays()));
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        return m;
    }

    private void update(MemoryDocument target, MemoryCandidate c, String conversationId, String content, Instant now) {
        target.setContent(content);
        target.setEmbedding(VectorMath.toList(embeddings.embedPassage(content)));
        target.setEmbeddingModel(embeddings.modelName());
        if (c.classification() == Classification.EXPLICIT) {
            target.setClassification(Classification.EXPLICIT);
        }
        target.setConfidence(cap(target.getClassification(), target.isUserVerified(),
                Math.max(target.getConfidence(), c.confidence())));
        target.setImportance(Math.max(target.getImportance(), c.importance()));
        if (c.sensitive()) {
            target.setSensitivity(Sensitivity.SENSITIVE);
        }
        if (target.getSourceConversationId() == null) {
            target.setSourceConversationId(conversationId);
        }
        List<String> sources = new ArrayList<>(
                target.getSourceMessageIds() == null ? List.of() : target.getSourceMessageIds());
        for (String id : c.sourceMessageIds()) {
            if (!sources.contains(id)) {
                sources.add(id);
            }
        }
        target.setSourceMessageIds(sources);
        if (target.getCategory() == Category.EMOTIONAL_CONTEXT && !target.isPinned()) {
            target.setExpiresAt(now.plus(java.time.Duration.ofDays(props.emotionalContextDays()))); // renewed
        }
        target.setUpdatedAt(now);
    }

    /** Joins two statements about the same thing; falls back to the newer one if the result would be too long. */
    static String combine(String existing, String candidate) {
        String ne = MemoryPolicy.normalize(existing);
        String nc = MemoryPolicy.normalize(candidate);
        if (nc.contains(ne)) {
            return candidate;
        }
        if (ne.contains(nc)) {
            return existing;
        }
        String joined = existing.strip().replaceAll("[.;]+$", "") + "; " + candidate.strip();
        return joined.length() <= MemoryPolicy.MAX_CONTENT_CHARS ? joined : candidate;
    }

    private static double cap(Classification classification, boolean userVerified, double confidence) {
        double c = MemoryPolicy.clamp01(confidence);
        return classification == Classification.INFERRED && !userVerified
                ? Math.min(c, MemoryPolicy.INFERRED_CONFIDENCE_CAP)
                : c;
    }
}
