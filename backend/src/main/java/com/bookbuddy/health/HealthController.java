package com.bookbuddy.health;

import com.bookbuddy.config.BookBuddyProperties;
import com.bookbuddy.embedding.EmbeddingStatus;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final MongoTemplate mongoTemplate;
    private final EmbeddingStatus embeddingStatus;
    private final BookBuddyProperties properties;

    public record HealthResponse(String status, boolean mongo, boolean embeddingLoaded, boolean llmConfigured) {}

    @GetMapping
    public HealthResponse health() {
        boolean mongoUp = pingMongo();
        return new HealthResponse(
                mongoUp ? "UP" : "DEGRADED",
                mongoUp,
                embeddingStatus.isLoaded(),
                properties.groq().configured());
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
