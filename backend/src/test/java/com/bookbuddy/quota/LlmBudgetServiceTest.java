package com.bookbuddy.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookbuddy.ai.TokenUsage;
import com.bookbuddy.quota.LlmBudgetService.Priority;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LlmBudgetServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    private final LlmUsageRepository usage = mock(LlmUsageRepository.class);
    private final LlmBudgetService budget = new LlmBudgetService(usage, 100, Clock.fixed(NOW, ZoneOffset.UTC));

    private void used(long n) {
        when(usage.countByDay("2026-06-01")).thenReturn(n);
    }

    @Test
    void lowerPrioritiesBackOffEarlier() {
        used(40);
        for (Priority p : Priority.values()) {
            assertTrue(budget.allows(p), p + " at 40%");
        }

        used(55);
        assertFalse(budget.allows(Priority.CONSOLIDATION), "consolidation stops at 50%");
        assertTrue(budget.allows(Priority.EXTRACTION));

        used(70);
        assertFalse(budget.allows(Priority.EXTRACTION), "extraction stops at 65%");
        assertTrue(budget.allows(Priority.SUMMARY));

        used(90);
        assertFalse(budget.allows(Priority.SUMMARY), "summaries stop at 85%");
        assertTrue(budget.allows(Priority.CHAT));

        used(100);
        assertFalse(budget.allows(Priority.CHAT), "chat stops at the limit");
        assertTrue(budget.allows(Priority.SAFETY), "the safety classifier is never starved");
    }

    @Test
    void priorityOrderIsSafetyChatSummaryExtractionConsolidation() {
        assertEquals(
                java.util.List.of(Priority.SAFETY, Priority.CHAT, Priority.SUMMARY, Priority.EXTRACTION, Priority.CONSOLIDATION),
                java.util.List.of(Priority.values()));
        double previous = Double.POSITIVE_INFINITY;
        for (Priority p : Priority.values()) {
            assertTrue(p.ceiling() <= previous);
            previous = p.ceiling();
        }
    }

    @Test
    void anUnreadableCounterNeverBlocksChatButPausesOptionalBackgroundWork() {
        when(usage.countByDay(anyString())).thenThrow(new IllegalStateException("db"));

        assertTrue(budget.allows(Priority.CHAT));
        assertTrue(budget.allows(Priority.SAFETY));
        assertTrue(budget.allows(Priority.SUMMARY));
        assertFalse(budget.allows(Priority.EXTRACTION));
        assertFalse(budget.allows(Priority.CONSOLIDATION));
    }

    @Test
    void deferredWorkIsRetriedLater() {
        assertEquals(NOW.plus(Duration.ofMinutes(10)), budget.retryAt());
    }

    @Test
    void aNonPositiveLimitFallsBackToTheDefault() {
        LlmBudgetService defaulted = new LlmBudgetService(usage, 0, Clock.fixed(NOW, ZoneOffset.UTC));
        used(899);
        assertTrue(defaulted.allows(Priority.CHAT));
        used(900);
        assertFalse(defaulted.allows(Priority.CHAT));
    }

    @Test
    void recordingStoresMetadataOnlyAndSwallowsFailures() {
        budget.record("u1", Priority.EXTRACTION, "model-x", TokenUsage.of(100, 20, false), 345, true);

        ArgumentCaptor<LlmUsageDocument> saved = ArgumentCaptor.forClass(LlmUsageDocument.class);
        verify(usage).save(saved.capture());
        LlmUsageDocument d = saved.getValue();
        assertEquals("u1", d.getUserId());
        assertEquals(Priority.EXTRACTION, d.getPurpose());
        assertEquals("2026-06-01", d.getDay());
        assertEquals(100, d.getPromptTokens());
        assertEquals(LlmUsageDocument.Outcome.OK, d.getOutcome());

        when(usage.save(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("db"));
        budget.record("u1", Priority.CHAT, null, null, 1, false); // must not throw
    }
}
