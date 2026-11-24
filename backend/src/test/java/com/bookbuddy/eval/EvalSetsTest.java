package com.bookbuddy.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.document.TextCleaningService;
import com.bookbuddy.rag.QuoteVerifier;
import com.bookbuddy.safety.RiskClassifier;
import com.bookbuddy.safety.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Keeps the golden sets in {@code docs/eval} well-formed and runs the parts that need no model or database:
 * the safety set against the deterministic rules (blocking), and the faithfulness set against the quote verifier.
 * See docs/eval/README.md for the live evals.
 */
class EvalSetsTest {

    private static final Path EVAL_DIR = locateEvalDir();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path locateEvalDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("docs").resolve("eval");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("docs/eval not found above " + Path.of("").toAbsolutePath());
    }

    private static List<JsonNode> read(String file) throws IOException {
        List<JsonNode> rows = new ArrayList<>();
        int lineNo = 0;
        for (String line : Files.readAllLines(EVAL_DIR.resolve(file), StandardCharsets.UTF_8)) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            try {
                rows.add(JSON.readTree(line));
            } catch (IOException e) {
                throw new AssertionError(file + ":" + lineNo + " is not valid JSON: " + e.getMessage());
            }
        }
        return rows;
    }

    private static void requireFields(String file, JsonNode row, String... fields) {
        for (String f : fields) {
            assertTrue(row.hasNonNull(f), file + " " + row.path("id").asText("?") + " is missing '" + f + "'");
        }
    }

    private static void assertUniqueIds(String file, List<JsonNode> rows) {
        Set<String> seen = new HashSet<>();
        for (JsonNode row : rows) {
            assertTrue(seen.add(row.path("id").asText()), file + " has a duplicate id " + row.path("id").asText());
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Test
    void retrievalSetIsWellFormed() throws IOException {
        List<JsonNode> rows = read("retrieval.jsonl");
        assertTrue(rows.size() >= 10, "expected at least 10 retrieval items");
        assertUniqueIds("retrieval.jsonl", rows);
        Set<String> langs = new HashSet<>();
        boolean hasNoAnswer = false;
        for (JsonNode r : rows) {
            requireFields("retrieval.jsonl", r, "id", "lang", "book", "question", "expectedPages", "answerable");
            assertTrue(r.get("expectedPages").isArray());
            assertEquals(r.get("answerable").asBoolean(), r.get("expectedPages").size() > 0,
                    r.get("id").asText() + ": answerable items need expectedPages, no-answer items must have none");
            langs.add(r.get("lang").asText());
            hasNoAnswer |= !r.get("answerable").asBoolean();
        }
        assertTrue(langs.containsAll(Set.of("en", "hi", "hinglish")), "need en, hi and hinglish items: " + langs);
        assertTrue(hasNoAnswer, "need at least one no-answer item");
    }

    @Test
    void safetySetIsWellFormedAndCoversEveryLanguageAndLevel() throws IOException {
        List<JsonNode> rows = read("safety.jsonl");
        assertUniqueIds("safety.jsonl", rows);
        Set<String> langs = new HashSet<>();
        Set<String> levels = new HashSet<>();
        for (JsonNode r : rows) {
            requireFields("safety.jsonl", r, "id", "lang", "text", "expectedLevel", "expectedBehavior", "rulesOnly");
            RiskLevel.valueOf(r.get("expectedLevel").asText()); // throws on a typo
            langs.add(r.get("lang").asText());
            levels.add(r.get("expectedLevel").asText());
        }
        assertEquals(Set.of("en", "hi", "hinglish"), langs);
        assertEquals(Set.of("ACUTE", "ELEVATED", "NONE"), levels);
    }

    /** Blocking: a regression here means a person in crisis could be missed (or a book question flagged). */
    @Test
    void safetySetPassesTheDeterministicRules() throws IOException {
        RiskClassifier classifier = new RiskClassifier(mock(LlmProvider.class));
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (JsonNode r : read("safety.jsonl")) {
            if (!r.get("rulesOnly").asBoolean()) {
                continue; // needs the LLM second opinion; run it live (see README)
            }
            checked++;
            RiskLevel expected = RiskLevel.valueOf(r.get("expectedLevel").asText());
            RiskLevel actual = classifier.classifyByRules(r.get("text").asText());
            if (actual != expected) {
                failures.add(r.get("id").asText() + " expected " + expected + " but got " + actual + ": "
                        + r.get("text").asText());
            }
        }
        assertTrue(checked >= 15, "too few rules-only safety items: " + checked);
        assertTrue(failures.isEmpty(), "Safety regressions:\n" + String.join("\n", failures));
    }

    @Test
    void llmDependentSafetyItemsNeverExpectLessThanTheRulesGive() throws IOException {
        // The LLM may only raise the level, so rules can never be *above* the expectation.
        RiskClassifier classifier = new RiskClassifier(mock(LlmProvider.class));
        for (JsonNode r : read("safety.jsonl")) {
            RiskLevel expected = RiskLevel.valueOf(r.get("expectedLevel").asText());
            RiskLevel byRules = classifier.classifyByRules(r.get("text").asText());
            assertTrue(byRules.ordinal() <= expected.ordinal(),
                    r.get("id").asText() + ": rules already give " + byRules + " but the set expects " + expected);
        }
    }

    @Test
    void faithfulnessSetIsWellFormed() throws IOException {
        List<JsonNode> rows = read("faithfulness.jsonl");
        assertUniqueIds("faithfulness.jsonl", rows);
        boolean fabricated = false;
        for (JsonNode r : rows) {
            requireFields("faithfulness.jsonl", r, "id", "question", "passages", "candidateAnswer", "expect");
            assertTrue(r.get("passages").isArray() && r.get("passages").size() > 0);
            assertTrue(r.get("expect").hasNonNull("verdict"));
            fabricated |= r.get("expect").path("fabricatedQuotes").size() > 0;
        }
        assertTrue(fabricated, "the set needs fabricated-quote cases");
    }

    /** The quote verifier must catch every fabricated quote in the set and keep every verbatim one. */
    @Test
    void quoteVerifierHandlesTheFaithfulnessSet() throws IOException {
        TextCleaningService cleaning = new TextCleaningService();
        QuoteVerifier verifier = new QuoteVerifier(cleaning);
        List<String> failures = new ArrayList<>();
        for (JsonNode r : read("faithfulness.jsonl")) {
            if (!r.path("offlineCheck").asBoolean(true)) {
                continue;
            }
            List<String> passages = new ArrayList<>();
            r.get("passages").forEach(p -> passages.add(p.get("text").asText()));
            String out = verifier.verify(r.get("candidateAnswer").asText(), passages).text();
            JsonNode expect = r.get("expect");
            String id = r.get("id").asText();

            for (JsonNode q : expect.path("fabricatedQuotes")) {
                if (isQuoted(out, q.asText())) {
                    failures.add(id + ": fabricated quote survived: " + q.asText());
                }
            }
            for (JsonNode q : expect.path("verbatimQuotes")) {
                if (!isQuoted(out, q.asText())) {
                    failures.add(id + ": verbatim quote was lost: " + q.asText());
                }
            }
            for (JsonNode bad : expect.path("mustNotContain")) {
                if (out.contains(bad.asText())) {
                    failures.add(id + ": contains forbidden text: " + bad.asText());
                }
            }
            if (expect.has("mustContainOneOf")) {
                boolean any = false;
                for (JsonNode ok : expect.get("mustContainOneOf")) {
                    any |= out.toLowerCase().contains(ok.asText().toLowerCase());
                }
                if (!any) {
                    failures.add(id + ": answer does not admit the gap");
                }
            }
        }
        assertTrue(failures.isEmpty(), "Faithfulness failures:\n" + String.join("\n", failures));
    }

    @Test
    void memorySetIsWellFormed() throws IOException {
        List<JsonNode> rows = read("memory.jsonl");
        assertTrue(rows.size() >= 8);
        assertUniqueIds("memory.jsonl", rows);
        Set<String> categories = Set.of(
                "PERSONAL_BACKGROUND", "INTEREST", "PREFERENCE", "GOAL", "EXPERIENCE", "PERSONALITY_OBSERVATION",
                "EMOTIONAL_CONTEXT");
        boolean hasNegative = false;
        for (JsonNode r : rows) {
            requireFields("memory.jsonl", r, "id", "focus", "sessions", "expect");
            assertTrue(r.get("sessions").size() > 0);
            for (JsonNode s : r.get("sessions")) {
                assertTrue(s.get("messages").size() > 0, r.get("id").asText());
                for (JsonNode m : s.get("messages")) {
                    assertTrue(Set.of("user", "assistant").contains(m.path("role").asText()), r.get("id").asText());
                    assertFalse(m.path("content").asText().isBlank());
                }
            }
            for (JsonNode e : r.get("expect").path("extract")) {
                assertTrue(categories.contains(e.path("category").asText()),
                        r.get("id").asText() + ": unknown category " + e.path("category").asText());
                assertTrue(Set.of("EXPLICIT", "INFERRED").contains(e.path("SourceType").asText()));
            }
            hasNegative |= r.get("expect").path("notExtract").size() > 0;
        }
        assertTrue(hasNegative, "the set needs expected non-extractions");
    }

    @Test
    void rubricAndReadmeExist() {
        assertTrue(Files.isRegularFile(EVAL_DIR.resolve("emotional-rubric.md")));
        assertTrue(Files.isRegularFile(EVAL_DIR.resolve("README.md")));
    }

    /** True when {@code text} appears wrapped in double quotes (straight or curly) in {@code out}. */
    private static boolean isQuoted(String out, String text) {
        return out.contains("\"" + text + "\"") || out.contains("\u201C" + text + "\u201D");
    }
}
