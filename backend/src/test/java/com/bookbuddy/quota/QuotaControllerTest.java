package com.bookbuddy.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class QuotaControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-15T20:00:00Z");
    private static final Instant RESET = Instant.parse("2026-11-16T00:00:00Z");

    private static QuotaController.QuotaStatus status(long used, int limit) {
        return QuotaController.toResponse(
                new LlmBudgetService.Status("2026-11-15", used, limit, (double) used / limit), RESET, NOW);
    }

    @Test
    void lowUsageIsOkAndNotPaused() {
        QuotaController.QuotaStatus s = status(100, 900);
        assertEquals(QuotaController.OK, s.state());
        assertEquals(11, s.percentUsed());
        assertFalse(s.backgroundPaused());
        assertEquals(4 * 3600, s.resetsInSeconds());
        assertEquals(RESET, s.resetsAt());
    }

    @Test
    void backgroundWorkBacksOffFromHalfTheQuota() {
        QuotaController.QuotaStatus s = status(500, 900);
        assertEquals(QuotaController.OK, s.state());
        assertTrue(s.backgroundPaused());
    }

    @Test
    void lowStateFromEightyFivePercent() {
        assertEquals(QuotaController.LOW, status(780, 900).state());
        assertEquals(QuotaController.OK, status(760, 900).state());
    }

    @Test
    void exhaustedIsCappedAtOneHundredPercent() {
        QuotaController.QuotaStatus s = status(1200, 900);
        assertEquals(QuotaController.EXHAUSTED, s.state());
        assertEquals(100, s.percentUsed());
    }

    @Test
    void budgetServiceResetsAtNextUtcMidnight() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        LlmBudgetService budget = new LlmBudgetService(mock(LlmUsageRepository.class), 900, clock);
        assertEquals(RESET, budget.resetsAt());
    }

    @Test
    void endpointDelegatesToTheBudget() {
        LlmBudgetService budget = mock(LlmBudgetService.class);
        when(budget.status()).thenReturn(new LlmBudgetService.Status("2026-11-15", 900, 900, 1.0));
        when(budget.resetsAt()).thenReturn(RESET);
        when(budget.now()).thenReturn(NOW);

        QuotaController.QuotaStatus s = new QuotaController(budget).status();

        assertEquals(QuotaController.EXHAUSTED, s.state());
        assertEquals(4 * 3600, s.resetsInSeconds());
    }
}
