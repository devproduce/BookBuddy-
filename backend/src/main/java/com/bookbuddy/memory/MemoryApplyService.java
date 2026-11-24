package com.bookbuddy.memory;

import com.bookbuddy.embedding.EmbeddingProvider;
import com.bookbuddy.embedding.VectorMath;
import com.bookbuddy.memory.MemoryDocument.SourceType;
import com.bookbuddy.memory.MemoryDocument.Status;
import com.bookbuddy.memory.MemoryEngineResult.InteractionDraft;
import com.bookbuddy.memory.MemoryEngineResult.MemoryConsolidation;
import com.bookbuddy.memory.MemoryEngineResult.MemoryUpdate;
import com.bookbuddy.memory.MemoryEngineResult.OutdatedMemory;
import com.bookbuddy.memory.MemoryEngineResult.ProfileDraft;
import com.bookbuddy.memory.MemoryMerger.Action;
import com.bookbuddy.memory.MemoryMerger.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Validates memory-engine proposals and persists them. Fabricated memory IDs are dropped; content changes are
 * re-embedded; profiles are upserted when the engine returns non-blank profile/interaction context.
 */
@Service
public class MemoryApplyService {

    public record ApplyResult(
            int added,
            int updated,
            int merged,
            int superseded,
            int ignored,
            int outdated,
            int consolidated,
            boolean profileUpdated,
            List<String> highImpactInferredIds) {

        String summary() {
            return "added=" + added + " updated=" + updated + " merged=" + merged + " superseded=" + superseded
                    + " ignored=" + ignored + " outdated=" + outdated + " consolidated=" + consolidated
                    + " profileUpdated=" + profileUpdated;
        }
    }

    private final MemoryRepository memories;
    private final UserProfileRepository profiles;
    private final MemoryMerger merger;
    private final MemoryWriter writer;
    private final EmbeddingProvider embeddings;
    private final MemoryProperties props;

    public MemoryApplyService(
            MemoryRepository memories,
            UserProfileRepository profiles,
            MemoryMerger merger,
            MemoryWriter writer,
            EmbeddingProvider embeddings,
            MemoryProperties props) {
        this.memories = memories;
        this.profiles = profiles;
        this.merger = merger;
        this.writer = writer;
        this.embeddings = embeddings;
        this.props = props;
    }

    public ApplyResult apply(String userId, String conversationId, MemoryEngineResult result) {
        Instant now = Instant.now();
        List<MemoryDocument> working = new ArrayList<>(
                memories.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, Status.ACTIVE));
        Map<String, MemoryDocument> byId = index(working);

        List<Outcome> outcomes = new ArrayList<>();
        int outdated = 0;
        int consolidated = 0;

        // 1) outdated
        for (OutdatedMemory o : result.outdatedMemories()) {
            MemoryDocument m = byId.get(o.memoryId());
            if (m == null || m.isPinned() || m.isStated()) {
                continue;
            }
            m.setStatus(Status.ARCHIVED);
            m.setUpdatedAt(now);
            outcomes.add(new Outcome(Action.IGNORE, null, m, null, 0));
            working.remove(m);
            byId.remove(m.getId());
            outdated++;
        }

        // 2) consolidations
        for (MemoryConsolidation c : result.memoryConsolidations()) {
            List<MemoryDocument> sources = new ArrayList<>();
            for (String id : c.sourceMemoryIds()) {
                MemoryDocument m = byId.get(id);
                if (m != null) {
                    sources.add(m);
                }
            }
            if (sources.size() < 2) {
                continue;
            }
            String content = MemoryPolicy.sanitize(c.consolidatedContent());
            if (content.length() < MemoryPolicy.MIN_CONTENT_CHARS) {
                continue;
            }
            MemoryDocument keep = MemoryConsolidationService.better(sources.get(0), sources.get(1));
            for (int i = 2; i < sources.size(); i++) {
                keep = MemoryConsolidationService.better(keep, sources.get(i));
            }
            keep.setContent(content);
            keep.setEmbedding(VectorMath.toList(embeddings.embedPassage(content)));
            keep.setEmbeddingModel(embeddings.modelName());
            keep.setUpdatedAt(now);
            keep.setRecurrence(Math.max(keep.getRecurrence(), sources.size()));
            outcomes.add(new Outcome(Action.MERGE, null, keep, null, 1.0));
            for (MemoryDocument drop : sources) {
                if (drop.getId().equals(keep.getId()) || drop.isPinned() || drop.isStated()) {
                    continue;
                }
                drop.setStatus(Status.ARCHIVED);
                drop.setSupersededBy(keep.getId());
                drop.setUpdatedAt(now);
                outcomes.add(new Outcome(Action.SUPERSEDE, null, null, drop, 1.0));
                working.remove(drop);
                byId.remove(drop.getId());
                consolidated++;
            }
            byId.put(keep.getId(), keep);
        }

        // 3) explicit updates by id
        for (MemoryUpdate u : result.memoryUpdates()) {
            MemoryDocument m = byId.get(u.existingMemoryId());
            if (m == null) {
                continue;
            }
            String content = MemoryPolicy.sanitize(u.updatedContent());
            if (content.length() < MemoryPolicy.MIN_CONTENT_CHARS) {
                continue;
            }
            m.setContent(content);
            m.setEmbedding(VectorMath.toList(embeddings.embedPassage(content)));
            m.setEmbeddingModel(embeddings.modelName());
            m.setConfidence(MemoryPolicy.clamp01(Math.max(m.getConfidence(), u.confidence())));
            m.setUpdatedAt(now);
            outcomes.add(new Outcome(Action.UPDATE, null, m, null, 1.0));
        }

        // 4) new memories via merger safety net
        for (MemoryCandidate c : result.newMemories()) {
            float[] embedding = embeddings.embedPassage(c.content());
            outcomes.add(merger.merge(c, userId, conversationId, embedding, working, now));
        }
        // refresh index after creates
        byId = index(working);

        writer.persist(outcomes);

        List<String> highImpact = new ArrayList<>();
        Set<String> confirmHints = new HashSet<>(result.processingMetadata().requiresUserConfirmation());
        for (MemoryDocument m : working) {
            if (m.getSourceType() == SourceType.INFERRED
                    && (m.isNeedsConfirmation() || confirmHints.contains(m.getId())
                            || m.getImportance() >= MemoryPolicy.HIGH_IMPACT_IMPORTANCE)) {
                if (!m.isNeedsConfirmation()) {
                    m.setNeedsConfirmation(true);
                    memories.save(m);
                }
                highImpact.add(m.getId());
            }
        }

        boolean profileUpdated = upsertProfile(userId, result.userProfile(), result.interactionContext(), now);

        int[] counts = new int[Action.values().length];
        for (Outcome o : outcomes) {
            counts[o.action().ordinal()]++;
        }
        return new ApplyResult(
                counts[Action.ADD.ordinal()],
                counts[Action.UPDATE.ordinal()],
                counts[Action.MERGE.ordinal()],
                counts[Action.SUPERSEDE.ordinal()],
                counts[Action.IGNORE.ordinal()],
                outdated,
                consolidated,
                profileUpdated,
                highImpact);
    }

    public boolean upsertProfile(
            String userId, ProfileDraft profile, InteractionDraft interaction, Instant now) {
        if ((profile == null || profile.isBlank()) && (interaction == null || interaction.isBlank())) {
            return false;
        }
        UserProfileDocument doc = profiles.findByUserId(userId).orElseGet(() -> {
            UserProfileDocument created = new UserProfileDocument();
            created.setId(new ObjectId().toHexString());
            created.setUserId(userId);
            created.setCreatedAt(now);
            return created;
        });
        if (profile != null && !profile.isBlank()) {
            if (!profile.background().isEmpty()) {
                doc.setBackground(new ArrayList<>(profile.background()));
            }
            if (!profile.interests().isEmpty()) {
                doc.setInterests(new ArrayList<>(profile.interests()));
            }
            if (!profile.goals().isEmpty()) {
                doc.setGoals(new ArrayList<>(profile.goals()));
            }
            if (!profile.values().isEmpty()) {
                doc.setValues(new ArrayList<>(profile.values()));
            }
            if (!profile.communicationPreferences().isEmpty()) {
                doc.setCommunicationPreferences(new ArrayList<>(profile.communicationPreferences()));
            }
            if (!profile.learningPreferences().isEmpty()) {
                doc.setLearningPreferences(new ArrayList<>(profile.learningPreferences()));
            }
            if (!profile.personalityObservations().isEmpty()) {
                doc.setPersonalityObservations(new ArrayList<>(profile.personalityObservations()));
            }
            if (!profile.thinkingPatterns().isEmpty()) {
                doc.setThinkingPatterns(new ArrayList<>(profile.thinkingPatterns()));
            }
            if (!profile.importantExperiences().isEmpty()) {
                doc.setImportantExperiences(new ArrayList<>(profile.importantExperiences()));
            }
            if (!profile.recurringChallenges().isEmpty()) {
                doc.setRecurringChallenges(new ArrayList<>(profile.recurringChallenges()));
            }
            if (!profile.currentActivities().isEmpty()) {
                doc.setCurrentActivities(new ArrayList<>(profile.currentActivities()));
            }
            if (!profile.profileSummary().isBlank()) {
                String s = profile.profileSummary()
                        .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                        .replace("<<<", "< < <")
                        .replace(">>>", "> > >")
                        .replaceAll("\\s+", " ")
                        .strip();
                doc.setProfileSummary(s.length() <= 1200 ? s : s.substring(0, 1200).stripTrailing());
            }
        }
        if (interaction != null && !interaction.isBlank()) {
            UserProfileDocument.InteractionContext ix = doc.getInteractionContext();
            if (ix == null) {
                ix = new UserProfileDocument.InteractionContext();
            }
            if (!interaction.preferredTone().isBlank()) {
                ix.setPreferredTone(interaction.preferredTone());
            }
            if (!interaction.preferredDetailLevel().isBlank()) {
                ix.setPreferredDetailLevel(interaction.preferredDetailLevel());
            }
            if (!interaction.communicationStyle().isEmpty()) {
                ix.setCommunicationStyle(new ArrayList<>(interaction.communicationStyle()));
            }
            if (!interaction.emotionalSupportPreferences().isEmpty()) {
                ix.setEmotionalSupportPreferences(new ArrayList<>(interaction.emotionalSupportPreferences()));
            }
            if (!interaction.advicePreferences().isEmpty()) {
                ix.setAdvicePreferences(new ArrayList<>(interaction.advicePreferences()));
            }
            if (!interaction.personalizationGuidelines().isEmpty()) {
                ix.setPersonalizationGuidelines(new ArrayList<>(interaction.personalizationGuidelines()));
            }
            if (!interaction.followUpQuestionPreference().isBlank()) {
                ix.setFollowUpQuestionPreference(interaction.followUpQuestionPreference());
            }
            doc.setInteractionContext(ix);
        }
        doc.setUpdatedAt(now);
        profiles.save(doc);
        return true;
    }

    private static Map<String, MemoryDocument> index(List<MemoryDocument> working) {
        Map<String, MemoryDocument> byId = new HashMap<>();
        for (MemoryDocument m : working) {
            byId.put(m.getId(), m);
        }
        return byId;
    }
}
