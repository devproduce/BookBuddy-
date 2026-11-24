package com.bookbuddy.memory;

import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.SourceType;
import com.bookbuddy.memory.MemoryDocument.Stability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates the extraction LLM's structured output for the legacy {@code memories[]} shape and the v2
 * {@code new_memories[]} items used by {@link MemoryEngineResultParser}.
 */
public final class MemoryCandidateParser {

    private MemoryCandidateParser() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code valid == false} means the whole reply was unusable. {@code discarded} counts dropped items. */
    public record Result(boolean valid, List<MemoryCandidate> candidates, int discarded) {

        static Result invalid() {
            return new Result(false, List.of(), 0);
        }
    }

    public static Result parse(String raw, Set<String> windowMessageIds) {
        JsonNode root = readJson(raw);
        if (root == null || !root.isObject()) {
            return Result.invalid();
        }
        JsonNode array = root.has("new_memories") && root.get("new_memories").isArray()
                ? root.get("new_memories")
                : root.get("memories");
        if (array == null || !array.isArray()) {
            return Result.invalid();
        }
        List<MemoryCandidate> out = new ArrayList<>();
        int discarded = 0;
        for (JsonNode item : array) {
            MemoryCandidate c = item(item, windowMessageIds, Set.of());
            if (c == null) {
                discarded++;
            } else {
                out.add(c);
            }
        }
        return new Result(true, out, discarded);
    }

    static MemoryCandidate item(JsonNode n, Set<String> windowMessageIds, Set<String> knownConversationIds) {
        if (n == null || !n.isObject()) {
            return null;
        }
        Category category = enumValue(Category.class, n.path("category"));
        SourceType sourceType = sourceType(n);
        if (category == null || sourceType == null) {
            return null;
        }
        JsonNode content = n.path("content");
        if (!content.isTextual()) {
            return null;
        }
        String text = content.asText().strip();
        if (text.length() < MemoryPolicy.MIN_CONTENT_CHARS || text.length() > MemoryPolicy.MAX_CONTENT_CHARS * 2) {
            return null;
        }
        Double confidence = unit(n.path("confidence"));
        Double importance = unit(n.path("importance"));
        if (confidence == null || importance == null) {
            return null;
        }
        int recurrence = n.path("recurrence").isNumber() ? Math.max(0, n.path("recurrence").asInt()) : 0;
        Stability stability = enumValue(Stability.class, n.path("stability"));
        if (stability == null) {
            stability = Stability.EVOLVING;
        }
        boolean sensitive = n.path("sensitive").asBoolean(false)
                || "SENSITIVE".equalsIgnoreCase(n.path("sensitivity").asText(""));
        boolean replaces = n.path("replaces_previous").asBoolean(false)
                || "OUTDATED".equalsIgnoreCase(n.path("status").asText(""));
        boolean needsConfirmation = n.path("needs_confirmation").asBoolean(false);
        List<String> evidence = stringList(n.path("evidence"));
        if (evidence.isEmpty() && n.path("evidence").isTextual()) {
            evidence = List.of(n.path("evidence").asText());
        }
        List<String> sources = filterIds(n.path("source_message_ids"), windowMessageIds);
        List<String> convIds = filterIds(n.path("source_conversation_ids"), knownConversationIds);
        if (knownConversationIds.isEmpty()) {
            // historical/incremental may omit known set; keep any non-blank ids
            convIds = stringList(n.path("source_conversation_ids"));
        }
        return new MemoryCandidate(
                category,
                text,
                sourceType,
                confidence,
                importance,
                recurrence,
                stability,
                sensitive,
                replaces,
                needsConfirmation,
                evidence,
                sources,
                convIds);
    }

    private static SourceType sourceType(JsonNode n) {
        SourceType fromSource = enumValue(SourceType.class, n.path("source_type"));
        if (fromSource != null) {
            return fromSource;
        }
        return enumValue(SourceType.class, n.path("classification"));
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

    private static List<String> filterIds(JsonNode n, Set<String> allowed) {
        List<String> out = new ArrayList<>();
        if (n == null || !n.isArray() || allowed == null || allowed.isEmpty()) {
            return out;
        }
        for (JsonNode id : n) {
            if (id.isTextual() && allowed.contains(id.asText())) {
                out.add(id.asText());
            }
        }
        return out;
    }

    /** A JSON number within 0..1; strings and out-of-range numbers are schema violations. */
    static Double unit(JsonNode n) {
        if (n == null || !n.isNumber()) {
            return null;
        }
        double v = n.asDouble();
        return v < 0 || v > 1 || Double.isNaN(v) ? null : v;
    }

    static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode n) {
        if (n == null || !n.isTextual()) {
            return null;
        }
        try {
            return Enum.valueOf(type, n.asText().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Tolerates a ```json fence or chatter around the object. */
    static JsonNode readJson(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JSON.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
