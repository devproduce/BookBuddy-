package com.bookbuddy.ai;

import com.bookbuddy.config.BookBuddyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Groq (OpenAI-compatible) chat completions via WebClient. */
@Service
public class GroqLlmProvider implements LlmProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final BookBuddyProperties.Groq config;
    private final WebClient client;

    @Autowired
    public GroqLlmProvider(BookBuddyProperties properties, WebClient.Builder builder) {
        this(properties.groq(), builder);
    }

    public GroqLlmProvider(BookBuddyProperties.Groq config, WebClient.Builder builder) {
        this.config = config;
        this.client = builder.baseUrl(config.baseUrl()).build();
    }

    @Override
    public boolean isConfigured() {
        return config.configured();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!isConfigured()) {
            throw new LlmException("Groq API key is not configured");
        }
        Map<String, Object> body = Map.of(
                "model", config.defaultModel(),
                "temperature", 0.2,
                "max_tokens", 900,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        try {
            JsonNode response = client.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
            String content = response == null
                    ? null
                    : response.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new LlmException("Groq returned an empty completion");
            }
            return content.strip();
        } catch (WebClientResponseException e) {
            throw new LlmException("Groq request failed with HTTP " + e.getStatusCode().value(), e);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("Groq request failed: " + e.getClass().getSimpleName(), e);
        }
    }
}
