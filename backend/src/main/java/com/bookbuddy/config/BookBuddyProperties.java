package com.bookbuddy.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly typed binding for the {@code bookbuddy.*} configuration tree.
 */
@ConfigurationProperties("bookbuddy")
public record BookBuddyProperties(
        Cors cors,
        Jwt jwt,
        Groq groq,
        Embedding embedding,
        Ingestion ingestion,
        Vector vector,
        Safety safety,
        Quota quota,
        Prompt prompt) {

    public BookBuddyProperties {
        if (cors == null) {
            cors = new Cors(List.of());
        }
        if (groq == null) {
            groq = new Groq("", "https://api.groq.com/openai/v1", List.of(), "");
        }
        if (safety == null) {
            safety = new Safety(List.of());
        }
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }

    public record Jwt(String secret, long expirationMs) {}

    public record Groq(String apiKey, String baseUrl, List<String> allowedModels, String defaultModel) {
        public Groq {
            allowedModels = allowedModels == null ? List.of() : List.copyOf(allowedModels);
        }

        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Embedding(
            boolean enabled, String modelPath, String tokenizerPath, int dimension, int maxTokens) {}

    public record Ingestion(
            String uploadDir, int maxPages, int minCharsPerPage, int chunkTokens, int chunkOverlapTokens) {}

    public record Vector(String indexName, int topN, int topK, double minSimilarity) {}

    public record Safety(List<CrisisResource> crisisResources) {
        public Safety {
            crisisResources = crisisResources == null ? List.of() : List.copyOf(crisisResources);
        }
    }

    public record CrisisResource(String name, String phone) {}

    public record Quota(int dailyRequestSoftLimit) {}

    public record Prompt(String chatSystemId) {}
}
