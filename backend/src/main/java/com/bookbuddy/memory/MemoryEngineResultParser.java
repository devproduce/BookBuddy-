package com.bookbuddy.memory;

import com.bookbuddy.memory.MemoryEngineResult.InteractionDraft;
import com.bookbuddy.memory.MemoryEngineResult.MemoryConsolidation;
import com.bookbuddy.memory.MemoryEngineResult.MemoryUpdate;
import com.bookbuddy.memory.MemoryEngineResult.Operation;
import com.bookbuddy.memory.MemoryEngineResult.OutdatedMemory;
import com.bookbuddy.memory.MemoryEngineResult.ProcessingMetadata;
import com.bookbuddy.memory.MemoryEngineResult.ProfileDraft;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses the memory engine §15 JSON contract into {@link MemoryEngineResult}. */
public final class MemoryEngineResultParser {

    private MemoryEngineResultParser() {}

    public record Result(boolean valid, MemoryEngineResult value) {
        static Result invalid() {
            return new Result(false, null);
        }
    }

    public static Result parse(String raw, Set<String> windowMessageIds, Set<String> knownConversationIds) {
        JsonNode root = MemoryCandidateParser.readJson(raw);
        if (root == null || !root.isObject()) {
            return Result.invalid();
        }
        Operation operation = enumValue(Operation.class, root.path("operation"));
        if (operation == null) {
            // tolerate missing operation when memories array is present (legacy extractor shape)
            if (root.has("memories") || root.has("new_memories")) {
                operation = Operation.INCREMENTAL_ANALYSIS;
            } else {
                return Result.invalid();
            }
        }

        List<MemoryCandidate> newMemories = new ArrayList<>();
        JsonNode memoriesNode = root.has("new_memories") ? root.get("new_memories") : root.get("memories");
        if (memoriesNode != null && memoriesNode.isArray()) {
            for (JsonNode item : memoriesNode) {
                MemoryCandidate c = MemoryCandidateParser.item(
                        item,
                        windowMessageIds == null ? Set.of() : windowMessageIds,
                        knownConversationIds == null ? Set.of() : knownConversationIds);
                if (c != null) {
                    newMemories.add(c);
                }
            }
        }

        List<MemoryUpdate> updates = new ArrayList<>();
        JsonNode updatesNode = root.path("memory_updates");
        if (updatesNode.isArray()) {
            for (JsonNode u : updatesNode) {
                String id = text(u.path("existing_memory_id"));
                String content = text(u.path("updated_content"));
                if (id.isBlank() || content.isBlank()) {
                    continue;
                }
                Double confidence = MemoryCandidateParser.unit(u.path("confidence"));
                updates.add(new MemoryUpdate(id, content, text(u.path("reason")), confidence == null ? 0.5 : confidence));
            }
        }

        List<MemoryConsolidation> consolidations = new ArrayList<>();
        JsonNode consNode = root.path("memory_consolidations");
        if (consNode.isArray()) {
            for (JsonNode c : consNode) {
                List<String> ids = stringList(c.path("source_memory_ids"));
                String content = text(c.path("consolidated_content"));
                if (ids.size() < 2 || content.isBlank()) {
                    continue;
                }
                consolidations.add(new MemoryConsolidation(ids, content, text(c.path("reason"))));
            }
        }

        List<OutdatedMemory> outdated = new ArrayList<>();
        JsonNode outNode = root.path("outdated_memories");
        if (outNode.isArray()) {
            for (JsonNode o : outNode) {
                String id = text(o.path("memory_id"));
                if (!id.isBlank()) {
                    outdated.add(new OutdatedMemory(id, text(o.path("reason"))));
                }
            }
        }

        return new Result(
                true,
                new MemoryEngineResult(
                        operation,
                        profile(root.path("user_profile")),
                        newMemories,
                        updates,
                        consolidations,
                        outdated,
                        stringList(root.path("uncertainties")),
                        interaction(root.path("interaction_context")),
                        metadata(root.path("processing_metadata")),
                        stringList(root.path("relevant_memory_ids")),
                        text(root.path("suggested_interaction_approach"))));
    }

    private static ProfileDraft profile(JsonNode n) {
        if (n == null || !n.isObject()) {
            return ProfileDraft.empty();
        }
        return new ProfileDraft(
                stringList(n.path("background")),
                stringList(n.path("interests")),
                stringList(n.path("goals")),
                stringList(n.path("values")),
                stringList(n.path("communication_preferences")),
                stringList(n.path("learning_preferences")),
                stringList(n.path("personality_observations")),
                stringList(n.path("thinking_patterns")),
                stringList(n.path("important_experiences")),
                stringList(n.path("recurring_challenges")),
                stringList(n.path("current_activities")),
                text(n.path("profile_summary")));
    }

    private static InteractionDraft interaction(JsonNode n) {
        if (n == null || !n.isObject()) {
            return InteractionDraft.empty();
        }
        return new InteractionDraft(
                text(n.path("preferred_tone")),
                text(n.path("preferred_detail_level")),
                stringList(n.path("communication_style")),
                stringList(n.path("emotional_support_preferences")),
                stringList(n.path("advice_preferences")),
                stringList(n.path("personalization_guidelines")),
                text(n.path("follow_up_question_preference")));
    }

    private static ProcessingMetadata metadata(JsonNode n) {
        if (n == null || !n.isObject()) {
            return ProcessingMetadata.empty();
        }
        return new ProcessingMetadata(
                n.path("conversations_analyzed").asInt(0),
                n.path("new_memories_count").asInt(0),
                n.path("updated_memories_count").asInt(0),
                n.path("consolidated_memories_count").asInt(0),
                stringList(n.path("requires_user_confirmation")));
    }

    private static List<String> stringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            for (JsonNode item : n) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    out.add(item.asText().strip());
                }
            }
        }
        return out;
    }

    private static String text(JsonNode n) {
        return n != null && n.isTextual() ? n.asText().strip() : "";
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode n) {
        if (n == null || !n.isTextual()) {
            return null;
        }
        try {
            return Enum.valueOf(type, n.asText().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
