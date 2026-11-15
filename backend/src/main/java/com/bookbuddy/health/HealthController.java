package com.bookbuddy.health;

import com.bookbuddy.config.BookBuddyProperties;
import com.bookbuddy.embedding.EmbeddingStatus;
import com.bookbuddy.quota.LlmBudgetService;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness and dependency summary. Deliberately coarse: it never exposes exact quota counters, secrets,
 * file paths or user data (see docs/observability.md).
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final MongoTemplate mongoTemplate;
    private final EmbeddingStatus embeddingStatus;
    private final BookBuddyProperties properties;
    private final LlmBudgetService budget;

    /**
     * @param embeddingModel name of the model producing vectors right now (hash fallback when not loaded)
     * @param vectorMode {@code atlas} or {@code memory}
     * @param llmQuota coarse daily-quota state: {@code OK}, {@code LOW} (>= 85% used) or {@code EXHAUSTED}
     */
    public record HealthResponse(
            String status,
            boolean mongo,
            boolean embeddingLoaded,
            boolean llmConfigured,
            String embeddingModel,
            String vectorMode,
            String llmQuota) {}

    @GetMapping
    public HealthResponse health() {
        boolean mongoUp = pingMongo();
        return new HealthResponse(
                mongoUp ? "UP" : "DEGRADED",
                mongoUp,
                embeddingStatus.isLoaded(),
                properties.groq().configured(),
                embeddingStatus.getActiveModel(),
                properties.vector().memoryOnly() ? "memory" : "atlas",
                mongoUp ? quotaState() : "UNKNOWN");
    }

    private String quotaState() {
        double used = budget.status().usedFraction();
        if (used >= 1.0) {
            return "EXHAUSTED";
        }
        return used >= LlmBudgetService.Priority.SUMMARY.ceiling() ? "LOW" : "OK";
    }

    private boolean pingMongo() {
        try {
            mongoTemplate.executeCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            log.warn("Mongo ping failed: {}", e.getMessage());
            return false;
        }
    }
}
