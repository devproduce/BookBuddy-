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
        if (embedding == null) {
            embedding = new Embedding(false, "models/model.onnx", "models/tokenizer.json", 384, 512);
        }
        if (ingestion == null) {
            ingestion = new Ingestion("uploads", 800, 40, 350, 60);
        }
        if (vector == null) {
            vector = new Vector("book_chunks_vector_index", 8, 5, 0.35, "atlas");
        }
        if (safety == null) {
            safety = new Safety(List.of());
        }
        if (prompt == null) {
            prompt = new Prompt(null, null);
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

    public record Vector(String indexName, int topN, int topK, double minSimilarity, String mode) {
        public Vector {
            if (mode == null || mode.isBlank()) {
                mode = "atlas";
            }
        }

        /** True when vector search must skip Atlas {@code $vectorSearch} and use the in-process brute-force path. */
        public boolean memoryOnly() {
            return "memory".equalsIgnoreCase(mode);
        }
    }

    public record Safety(List<CrisisResource> crisisResources) {
        public Safety {
            crisisResources = crisisResources == null ? List.of() : List.copyOf(crisisResources);
        }
    }

    public record CrisisResource(String name, String phone) {}

    public record Quota(int dailyRequestSoftLimit) {}

    /**
     * Ids of versioned prompt files ({@code classpath:prompt/<id>.txt}). Blank values fall back to the defaults so
     * a prompt can be rolled back by config alone (e.g. {@code CHAT_PROMPT_ID=chat.system.v1}).
     */
    public record Prompt(String chatSystemId, String queryRewriteId) {

        public static final String DEFAULT_CHAT_SYSTEM_ID = "chat.system.v3";
        public static final String DEFAULT_QUERY_REWRITE_ID = "query-rewrite.v1";

        public Prompt {
            chatSystemId = chatSystemId == null || chatSystemId.isBlank() ? DEFAULT_CHAT_SYSTEM_ID : chatSystemId.strip();
            queryRewriteId = queryRewriteId == null || queryRewriteId.isBlank()
                    ? DEFAULT_QUERY_REWRITE_ID
                    : queryRewriteId.strip();
        }
    }
}
