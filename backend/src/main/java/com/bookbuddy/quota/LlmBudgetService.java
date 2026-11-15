package com.bookbuddy.quota;

import com.bookbuddy.ai.TokenUsage;
import com.bookbuddy.config.BookBuddyProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * One free LLM quota is shared by safety checks, live chat, summaries, memory extraction and consolidation. Calls are
 * counted per UTC day against {@code bookbuddy.quota.daily-request-soft-limit}; each {@link Priority} may keep
 * spending only while usage is below its share of that limit, so the lower the priority, the earlier it backs off:
 *
 * <pre>
 * safety (always) &gt; chat (100%) &gt; summary (85%) &gt; extraction (65%) &gt; consolidation (50%)
 * </pre>
 *
 * Background work that is refused should be deferred (see {@code JobWorker}), never dropped.
 */
@Service
public class LlmBudgetService {

    private static final Logger log = LoggerFactory.getLogger(LlmBudgetService.class);

    /** Highest priority first. {@code ceiling} is the fraction of the daily limit up to which the purpose may run. */
    public enum Priority {
        SAFETY(Double.POSITIVE_INFINITY),
        CHAT(1.0),
        SUMMARY(0.85),
        EXTRACTION(0.65),
        CONSOLIDATION(0.5);

        private final double ceiling;

        Priority(double ceiling) {
            this.ceiling = ceiling;
        }

        public double ceiling() {
            return ceiling;
        }

        /** True for work that can wait and retry later. */
        public boolean isBackground() {
            return this == SUMMARY || this == EXTRACTION || this == CONSOLIDATION;
        }
    }

    /** Snapshot for diagnostics. */
    public record Status(String day, long used, int limit, double usedFraction) {}

    /** How long refused background work waits before it is looked at again. */
    public static final Duration DEFER_FOR = Duration.ofMinutes(10);

    private final LlmUsageRepository usage;
    private final int dailyLimit;
    private final Clock clock;

    @Autowired
    public LlmBudgetService(LlmUsageRepository usage, BookBuddyProperties properties) {
        this(usage, properties.quota() == null ? 0 : properties.quota().dailyRequestSoftLimit(), Clock.systemUTC());
    }

    public LlmBudgetService(LlmUsageRepository usage, int dailyLimit, Clock clock) {
        this.usage = usage;
        this.dailyLimit = dailyLimit > 0 ? dailyLimit : 900;
        this.clock = clock;
    }

    /** May a call of this priority be made right now? Never throws: an unreadable counter must not block chat. */
    public boolean allows(Priority priority) {
        if (priority == Priority.SAFETY) {
            return true;
        }
        try {
            long used = usage.countByDay(today());
            return used < dailyLimit * priority.ceiling();
        } catch (RuntimeException e) {
            log.debug("Budget counter unavailable ({}); allowing {}", e.getClass().getSimpleName(), priority);
            return !priority.isBackground() || priority == Priority.SUMMARY;
        }
    }

    public Status status() {
        long used = 0;
        try {
            used = usage.countByDay(today());
        } catch (RuntimeException e) {
            log.debug("Budget counter unavailable: {}", e.getClass().getSimpleName());
        }
        return new Status(today(), used, dailyLimit, dailyLimit == 0 ? 0 : (double) used / dailyLimit);
    }

    /** When the UTC-day counter starts again. */
    public Instant resetsAt() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** Current time as seen by the budget (for computing "resets in" values consistently). */
    public Instant now() {
        return clock.instant();
    }

    /** When refused background work should be retried. */
    public Instant retryAt() {
        return clock.instant().plus(DEFER_FOR);
    }

    /** Records a finished call (successful or not). Failures to record are swallowed. */
    public void record(
            String userId, Priority purpose, String model, TokenUsage tokens, long latencyMs, boolean ok) {
        try {
            LlmUsageDocument d = new LlmUsageDocument();
            d.setUserId(userId);
            d.setPurpose(purpose);
            d.setModel(model);
            d.setPromptTokens(tokens == null ? 0 : tokens.promptTokens());
            d.setCompletionTokens(tokens == null ? 0 : tokens.completionTokens());
            d.setLatencyMs(latencyMs);
            d.setOutcome(ok ? LlmUsageDocument.Outcome.OK : LlmUsageDocument.Outcome.ERROR);
            d.setDay(today());
            d.setCreatedAt(clock.instant().truncatedTo(ChronoUnit.MILLIS));
            usage.save(d);
        } catch (RuntimeException e) {
            log.debug("Could not record LLM usage: {}", e.getClass().getSimpleName());
        }
    }

    private String today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
    }
}
