package com.bookbuddy.memory;

import static com.bookbuddy.memory.MemoryTestSupport.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bookbuddy.ai.LlmException;
import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.conversation.MessageDocument;
import com.bookbuddy.conversation.MessageDocument.Role;
import com.bookbuddy.jobs.JobHandler.BudgetDeferredException;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Classification;
import com.bookbuddy.memory.MemoryExtractor.InvalidOutputException;
import com.bookbuddy.quota.LlmBudgetService;
import com.bookbuddy.quota.LlmBudgetService.Priority;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Schema validation, retry-once, the safety rules applied to LLM output, and failure behaviour. */
class MemoryExtractorTest {

    private final LlmProvider llm = mock(LlmProvider.class);
    private final LlmBudgetService budget = mock(LlmBudgetService.class);
    private MemoryExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new MemoryExtractor(llm, budget, MemoryProperties.defaults());
        when(budget.allows(any())).thenReturn(true);
    }

    private static MessageDocument msg(String id, Role role, String text) {
        MessageDocument m = new MessageDocument();
        m.setId(id);
        m.setRole(role);
        m.setContent(text);
        return m;
    }

    private static final List<MessageDocument> WINDOW = List.of(
            msg("m1", Role.USER, "I love slow-burn fantasy and I'm trying to read 30 books this year"),
            msg("m2", Role.ASSISTANT, "Great goal! Have you tried The Name of the Wind?"),
            msg("m3", Role.USER, "My doctor says I have anxiety so I prefer calm stories"));

    private static String item(String category, String content, String classification, double confidence, String evidence) {
        return "{\"category\":\"" + category + "\",\"content\":\"" + content + "\",\"classification\":\""
                + classification + "\",\"confidence\":" + confidence + ",\"importance\":0.6,"
                + "\"evidence\":\"" + evidence + "\",\"source_message_ids\":[\"m1\",\"zzz\"]}";
    }

    private static String reply(String... items) {
        return "{\"memories\":[" + String.join(",", items) + "]}";
    }

    // ---------------------------------------------------------------------------------------------------------
    // Parser
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void parserAcceptsAValidDocumentAndKeepsOnlyRealSourceIds() {
        var r = MemoryCandidateParser.parse(
                reply(item("INTERESTS", "Loves slow-burn fantasy", "EXPLICIT", 0.9, "I love slow-burn fantasy")),
                Set.of("m1", "m2"));

        assertTrue(r.valid());
        assertEquals(1, r.candidates().size());
        MemoryCandidate c = r.candidates().get(0);
        assertEquals(Category.INTERESTS, c.category());
        assertEquals(Classification.EXPLICIT, c.classification());
        assertEquals(List.of("m1"), c.sourceMessageIds(), "ids outside the window are dropped");
    }

    @Test
    void parserToleratesCodeFencesAndChatter() {
        String raw = "Sure! ```json\n" + reply(item("GOALS", "Wants to read 30 books", "EXPLICIT", 0.9, "30 books")) + "\n```";
        assertTrue(MemoryCandidateParser.parse(raw, Set.of()).valid());
    }

    @Test
    void parserRejectsDocumentsWithoutAMemoriesArray() {
        assertFalse(MemoryCandidateParser.parse("not json at all", Set.of()).valid());
        assertFalse(MemoryCandidateParser.parse("{\"items\":[]}", Set.of()).valid());
        assertFalse(MemoryCandidateParser.parse("{\"memories\":\"none\"}", Set.of()).valid());
        assertFalse(MemoryCandidateParser.parse(null, Set.of()).valid());
        assertTrue(MemoryCandidateParser.parse("{\"memories\":[]}", Set.of()).valid(), "an empty list is a valid answer");
    }

    @Test
    void parserDiscardsInvalidItemsIndividually() {
        String raw = reply(
                item("INTERESTS", "Valid one", "EXPLICIT", 0.9, "valid"),
                item("NOT_A_CATEGORY", "Bad category", "EXPLICIT", 0.9, "x"),
                item("INTERESTS", "Bad class", "MAYBE", 0.9, "x"),
                item("INTERESTS", "Confidence out of range", "EXPLICIT", 1.5, "x"),
                "{\"category\":\"INTERESTS\",\"content\":\"Confidence as text\",\"classification\":\"EXPLICIT\","
                        + "\"confidence\":\"high\",\"importance\":0.5}",
                "{\"category\":\"INTERESTS\",\"classification\":\"EXPLICIT\",\"confidence\":0.9,\"importance\":0.5}",
                "\"just a string\"");

        var r = MemoryCandidateParser.parse(raw, Set.of());

        assertTrue(r.valid());
        assertEquals(1, r.candidates().size());
        assertEquals(6, r.discarded());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Extraction
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void validCandidatesAreReturnedAndUsageIsRecordedWithoutContent() {
        when(llm.complete(anyString(), anyString())).thenReturn(reply(
                item("INTERESTS", "Loves slow-burn fantasy", "EXPLICIT", 0.9, "I love slow-burn fantasy")));

        List<MemoryCandidate> out = extractor.extract(USER, WINDOW);

        assertEquals(1, out.size());
        assertEquals(Classification.EXPLICIT, out.get(0).classification());
        verify(budget).record(eq(USER), eq(Priority.EXTRACTION), isNull(), any(), anyLong(), eq(true));
    }

    @Test
    void sensitiveInferencesFromTheModelAreDroppedButStatedOnesSurvive() {
        when(llm.complete(anyString(), anyString())).thenReturn(reply(
                item("ABOUT_ME", "Has depression", "INFERRED", 0.9, ""),
                item("ABOUT_ME", "Has anxiety", "EXPLICIT", 0.9, "I have anxiety"),
                item("INTERESTS", "Loves slow-burn fantasy", "INFERRED", 0.95, "")));

        List<MemoryCandidate> out = extractor.extract(USER, WINDOW);

        assertEquals(List.of("Has anxiety", "Loves slow-burn fantasy"), out.stream().map(MemoryCandidate::content).toList());
        assertTrue(out.get(0).sensitive());
        assertEquals(Classification.EXPLICIT, out.get(0).classification());
        assertEquals(MemoryPolicy.INFERRED_CONFIDENCE_CAP, out.get(1).confidence(), 1e-9);
    }

    @Test
    void anExplicitClaimQuotingWordsTheUserNeverWroteIsDowngraded() {
        when(llm.complete(anyString(), anyString())).thenReturn(reply(
                item("INTERESTS", "Loves horror", "EXPLICIT", 0.9, "I love horror")));

        List<MemoryCandidate> out = extractor.extract(USER, WINDOW);

        assertEquals(Classification.INFERRED, out.get(0).classification());
    }

    @Test
    void retriesOnceWhenTheFirstReplyIsInvalid() {
        when(llm.complete(anyString(), anyString()))
                .thenReturn("Sorry, here are some thoughts...")
                .thenReturn(reply(item("INTERESTS", "Loves slow-burn fantasy", "EXPLICIT", 0.9, "I love slow-burn fantasy")));

        List<MemoryCandidate> out = extractor.extract(USER, WINDOW);

        assertEquals(1, out.size());
        verify(llm, times(2)).complete(anyString(), anyString());
        verify(llm).complete(anyString(), contains("did not match the required JSON schema"));
    }

    @Test
    void twoInvalidRepliesFailTheRunWithoutAnyCandidates() {
        when(llm.complete(anyString(), anyString())).thenReturn("nope");

        assertThrows(InvalidOutputException.class, () -> extractor.extract(USER, WINDOW));
        verify(llm, times(2)).complete(anyString(), anyString());
    }

    @Test
    void providerFailuresPropagateSoNothingIsWritten() {
        when(llm.complete(anyString(), anyString())).thenThrow(new LlmException("429"));

        assertThrows(LlmException.class, () -> extractor.extract(USER, WINDOW));
        verify(budget).record(eq(USER), eq(Priority.EXTRACTION), isNull(), isNull(), anyLong(), eq(false));
    }

    @Test
    void aLowBudgetDefersWithoutCallingTheModel() {
        when(budget.allows(Priority.EXTRACTION)).thenReturn(false);

        assertThrows(BudgetDeferredException.class, () -> extractor.extract(USER, WINDOW));
        verifyNoInteractions(llm);
    }

    @Test
    void theBudgetIsRecheckedBeforeTheRetry() {
        when(budget.allows(Priority.EXTRACTION)).thenReturn(true, false);
        when(llm.complete(anyString(), anyString())).thenReturn("garbage");

        assertThrows(BudgetDeferredException.class, () -> extractor.extract(USER, WINDOW));
        verify(llm, times(1)).complete(anyString(), anyString());
    }

    @Test
    void windowsWithoutUserTextNeverReachTheModel() {
        List<MemoryCandidate> out = extractor.extract(USER, List.of(msg("a", Role.ASSISTANT, "Hello!")));

        assertTrue(out.isEmpty());
        verifyNoInteractions(llm);
        verify(budget, never()).record(anyString(), any(), any(), any(), anyLong(), anyBoolean());
    }

    @Test
    void candidateCountIsCapped() {
        MemoryExtractor small = new MemoryExtractor(llm, budget, new MemoryProperties(0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0));
        when(llm.complete(anyString(), anyString())).thenReturn(reply(
                item("INTERESTS", "One", "INFERRED", 0.6, ""),
                item("INTERESTS", "Two", "INFERRED", 0.6, ""),
                item("INTERESTS", "Three", "INFERRED", 0.6, "")));

        assertEquals(2, small.extract(USER, WINDOW).size());
    }

    @Test
    void transcriptLabelsRolesAndCollectsOnlyUserText() {
        Set<String> ids = new java.util.HashSet<>();
        StringBuilder userText = new StringBuilder();

        String transcript = MemoryExtractor.transcript(WINDOW, ids, userText);

        assertTrue(transcript.contains("[m1] USER: I love slow-burn fantasy"));
        assertTrue(transcript.contains("[m2] BOOK BUDDY: Great goal!"));
        assertEquals(Set.of("m1", "m2", "m3"), ids);
        assertTrue(userText.toString().contains("slow-burn"));
        assertFalse(userText.toString().contains("Name of the Wind"), "assistant words are never evidence about the user");
    }
}
