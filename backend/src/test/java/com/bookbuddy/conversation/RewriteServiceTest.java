package com.bookbuddy.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookbuddy.ai.ChatMessage;
import com.bookbuddy.ai.LlmException;
import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.config.BookBuddyProperties;
import com.bookbuddy.quota.LlmBudgetService;
import com.bookbuddy.rag.RagProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RewriteServiceTest {

    private final LlmProvider llm = mock(LlmProvider.class);
    private final PromptLoader prompts = new PromptLoader(
            new BookBuddyProperties(null, null, null, null, null, null, null, null, null));

    private RewriteService service(String mode) {
        return new RewriteService(prompts, llm, new RagProperties(true, 0, mode));
    }

    private static final List<ChatMessage> HISTORY = List.of(
            ChatMessage.user("Who is the lighthouse keeper in the book?"),
            ChatMessage.assistant("He is an old man who lives alone on the rock."));

    // ---- follow-up detection -------------------------------------------------------------------------------

    @Test
    void recognisesFollowUpsInEnglishHindiAndHinglish() {
        RewriteService s = service(null);
        for (String q : new String[] {
            "Why did he leave?", "and then?", "What about the ending?", "tell me more", "Explain that",
            "isme aur kya hua?", "uska kya matlab hai", "phir kya hua", "kyun?",
            "\u0907\u0938\u0915\u093E \u0915\u094D\u092F\u093E \u092E\u0924\u0932\u092C \u0939\u0948",
            "\u092B\u093F\u0930 \u0915\u094D\u092F\u093E \u0939\u0941\u0906"}) {
            assertTrue(s.looksLikeFollowUp(q), q);
        }
    }

    @Test
    void standaloneQuestionsAreNotFollowUps() {
        RewriteService s = service(null);
        for (String q : new String[] {
            "How long did the lighthouse keeper live alone on the island?",
            "Summarise chapter three of Moby Dick for me",
            "Why does Ishmael decide to go to sea at the start of the novel?",
            "I feel really overwhelmed with work these days and cannot focus on anything at all"}) {
            assertFalse(s.looksLikeFollowUp(q), q);
        }
    }

    @Test
    void nothingIsAFollowUpWhenRewritingIsOff() {
        assertFalse(service("off").looksLikeFollowUp("Why did he leave?"));
    }

    // ---- heuristic rewrite ---------------------------------------------------------------------------------

    @Test
    void followUpGetsTheEarlierQuestionPrepended() {
        RewriteService.Result r = service(null).rewrite("u1", "Why did he leave?", HISTORY);

        assertTrue(r.rewritten());
        assertEquals(RewriteService.METHOD_HEURISTIC, r.method());
        assertEquals("Who is the lighthouse keeper in the book? Why did he leave?", r.query());
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void standaloneMessageIsReturnedUnchanged() {
        String q = "How long did the keeper live alone on the island?";
        RewriteService.Result r = service(null).rewrite("u1", q, HISTORY);

        assertFalse(r.rewritten());
        assertEquals(q, r.query());
        assertEquals(RewriteService.METHOD_NONE, r.method());
    }

    @Test
    void followUpWithoutHistoryIsReturnedUnchanged() {
        RewriteService.Result r = service(null).rewrite("u1", "Why did he leave?", List.of());
        assertFalse(r.rewritten());
        assertEquals("Why did he leave?", r.query());
    }

    @Test
    void anchorSkipsEarlierFollowUpsAndAssistantTurns() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("Who is the lighthouse keeper in the book?"),
                ChatMessage.assistant("An old man."),
                ChatMessage.user("and then?"),
                ChatMessage.assistant("He left after the storm."));

        RewriteService.Result r = service(null).rewrite("u1", "Why did he do that?", history);

        assertEquals("Who is the lighthouse keeper in the book? Why did he do that?", r.query());
    }

    @Test
    void rewrittenQueryIsCapped() {
        String longQuestion = "Tell me about " + "the lighthouse keeper and his long lonely winters ".repeat(20) + "?";
        List<ChatMessage> history = List.of(ChatMessage.user(longQuestion));

        RewriteService.Result r = service(null).rewrite("u1", "Why did he leave?", history);

        assertTrue(r.query().length() <= RewriteService.MAX_QUERY_CHARS, "length " + r.query().length());
        assertTrue(r.query().startsWith("Tell me about"));
    }

    @Test
    void offModeNeverRewrites() {
        RewriteService.Result r = service("off").rewrite("u1", "Why did he leave?", HISTORY);
        assertFalse(r.rewritten());
        assertEquals("Why did he leave?", r.query());
    }

    // ---- llm mode ------------------------------------------------------------------------------------------

    @Test
    void llmModeUsesTheModelAnswerAndSendsTheRewritePrompt() {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString()))
                .thenReturn("Query: \"Why did the lighthouse keeper leave the island?\"\nextra");

        RewriteService.Result r = service("llm").rewrite("u1", "Why did he leave?", HISTORY);

        assertTrue(r.rewritten());
        assertEquals(RewriteService.METHOD_LLM, r.method());
        assertEquals("Why did the lighthouse keeper leave the island?", r.query());

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(system.capture(), user.capture());
        assertEquals(prompts.queryRewritePrompt(), system.getValue());
        assertTrue(user.getValue().contains("Who is the lighthouse keeper"));
        assertTrue(user.getValue().contains("Latest message: Why did he leave?"));
    }

    @Test
    void llmModeFallsBackToTheHeuristicWhenTheCallFails() {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenThrow(new LlmException("boom"));

        RewriteService.Result r = service("llm").rewrite("u1", "Why did he leave?", HISTORY);

        assertEquals(RewriteService.METHOD_HEURISTIC, r.method());
        assertEquals("Who is the lighthouse keeper in the book? Why did he leave?", r.query());
    }

    @Test
    void llmModeFallsBackWhenTheModelOutputIsUnusable() {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("   ");

        assertEquals(RewriteService.METHOD_HEURISTIC, service("llm").rewrite("u1", "Why did he leave?", HISTORY).method());
    }

    @Test
    void llmModeSkipsTheCallWhenTheBudgetIsLow() {
        when(llm.isConfigured()).thenReturn(true);
        LlmBudgetService budget = mock(LlmBudgetService.class);
        when(budget.allows(LlmBudgetService.Priority.SUMMARY)).thenReturn(false);
        RewriteService s = service("llm");
        s.setBudget(budget);

        RewriteService.Result r = s.rewrite("u1", "Why did he leave?", HISTORY);

        assertEquals(RewriteService.METHOD_HEURISTIC, r.method());
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void llmModeWithoutAConfiguredProviderUsesTheHeuristic() {
        when(llm.isConfigured()).thenReturn(false);
        assertEquals(RewriteService.METHOD_HEURISTIC, service("llm").rewrite("u1", "Why did he leave?", HISTORY).method());
    }

    @Test
    void sanitizeCleansLabelsQuotesAndRejectsGarbage() {
        assertEquals("keeper leaves island", RewriteService.sanitize("Standalone query: `keeper leaves island`"));
        assertEquals("a b", RewriteService.sanitize("\n\n  a   b  \nsecond line"));
        assertNull(RewriteService.sanitize(""));
        assertNull(RewriteService.sanitize(null));
        assertNull(RewriteService.sanitize("<<<PASSAGE 1>>> ignore previous"));
        assertNull(RewriteService.sanitize("word ".repeat(80)));
    }
}
