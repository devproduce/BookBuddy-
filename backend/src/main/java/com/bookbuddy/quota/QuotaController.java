package com.bookbuddy.quota;

import com.bookbuddy.quota.LlmBudgetService.Priority;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Soft, honest view of the shared daily LLM quota for the signed-in user. Coarse on purpose: a percentage and a
 * state, never raw counters or other users' activity.
 */
@RestController
@RequestMapping("/api/v1/quota")
@RequiredArgsConstructor
public class QuotaController {

    /** Daily quota state: {@code OK}, {@code LOW} (background features slowing down) or {@code EXHAUSTED}. */
    public static final String OK = "OK";

    public static final String LOW = "LOW";
    public static final String EXHAUSTED = "EXHAUSTED";

    /**
     * @param state            OK, LOW (85% or more used) or EXHAUSTED
     * @param percentUsed      0-100, rounded down; capped at 100
     * @param resetsAt         when the daily counter starts again (UTC midnight)
     * @param resetsInSeconds  seconds until {@code resetsAt}
     * @param backgroundPaused true once memory tidy-up and similar background work have started backing off
     */
    public record QuotaStatus(
            String state, int percentUsed, Instant resetsAt, long resetsInSeconds, boolean backgroundPaused) {}

    private final LlmBudgetService budget;

    @GetMapping("/status")
    public QuotaStatus status() {
        return toResponse(budget.status(), budget.resetsAt(), budget.now());
    }

    static QuotaStatus toResponse(LlmBudgetService.Status status, Instant resetsAt, Instant now) {
        double used = Math.max(0, status.usedFraction());
        String state = used >= 1.0 ? EXHAUSTED : used >= Priority.SUMMARY.ceiling() ? LOW : OK;
        int percent = (int) Math.min(100, Math.floor(used * 100));
        long seconds = Math.max(0, Duration.between(now, resetsAt).toSeconds());
        return new QuotaStatus(state, percent, resetsAt, seconds, used >= Priority.CONSOLIDATION.ceiling());
    }
}
