package com.bookbuddy.memory;

import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Classification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates the extraction LLM's structured output.
 *
 * <pre>
 * {"memories":[{
 *   "category":"INTERESTS",            // one of the Category names
 *   "content":"Loves slow-burn fantasy", // 3..300 chars
 *   "classification":"EXPLICIT",       // EXPLICIT | INFERRED
 *   "confidence":0.9,                  // number 0..1
 *   "importance":0.6,                  // number 0..1
 *   "sensitive":false,                 // optional boolean
 *   "replaces_previous":false,         // optional boolean
 *   "evidence":"I love slow-burn fantasy", // optional; the user's own words
 *   "source_message_ids":["..."]       // optional
 * }]}
 * </pre>
 *
 * The document is all-or-nothing at the top level (not JSON, or no {@code memories} array: invalid, the caller
 * retries); individual malformed items are discarded and counted.
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
        if (root == null || !root.isObject() || !root.has("memories") || !root.get("memories").isArray()) {
            return Result.invalid();
        }
        List<MemoryCandidate> out = new ArrayList<>();
        int discarded = 0;
        for (JsonNode item : root.get("memories")) {
            MemoryCandidate c = item(item, windowMessageIds);
            if (c == null) {
                discarded++;
            } else {
                out.add(c);
            }
        }
        return new Result(true, out, discarded);
    }

    private static MemoryCandidate item(JsonNode n, Set<String> windowMessageIds) {
        if (n == null || !n.isObject()) {
            return null;
        }
        Category category = enumValue(Category.class, n.path("category"));
        Classification classification = enumValue(Classification.class, n.path("classification"));
        if (category == null || classification == null) {
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
        boolean sensitive = n.path("sensitive").asBoolean(false);
        boolean replaces = n.path("replaces_previous").asBoolean(false);
        String evidence = n.path("evidence").isTextual() ? n.path("evidence").asText() : "";
        List<String> sources = new ArrayList<>();
        JsonNode ids = n.path("source_message_ids");
        if (ids.isArray()) {
            for (JsonNode id : ids) {
                if (id.isTextual() && windowMessageIds.contains(id.asText())) {
                    sources.add(id.asText());
                }
            }
        }
        return new MemoryCandidate(
                category, text, classification, confidence, importance, sensitive, replaces, evidence, sources);
    }

    /** A JSON number within 0..1; strings and out-of-range numbers are schema violations. */
    private static Double unit(JsonNode n) {
        if (n == null || !n.isNumber()) {
            return null;
        }
        double v = n.asDouble();
        return v < 0 || v > 1 || Double.isNaN(v) ? null : v;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode n) {
        if (!n.isTextual()) {
            return null;
        }
        try {
            return Enum.valueOf(type, n.asText().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Tolerates a ```json fence or chatter around the object. */
    private static JsonNode readJson(String raw) {
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
