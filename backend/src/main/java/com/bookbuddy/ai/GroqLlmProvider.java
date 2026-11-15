package com.bookbuddy.ai;

import com.bookbuddy.config.BookBuddyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
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
    /** Maximum silence between two stream events before the call is abandoned. */
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofSeconds(45);
    private static final long STREAM_MAX_SECONDS = 180;
    private static final ObjectMapper JSON = new ObjectMapper();

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
            throw LlmException.http(e.getStatusCode().value(), retryAfter(e), e);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("Groq request failed: " + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public StreamResult streamChat(
            List<ChatMessage> messages, ChatOptions options, Consumer<String> onToken, CancelToken cancel) {
        if (!isConfigured()) {
            throw new LlmException("Groq API key is not configured");
        }
        ChatOptions opts = options == null ? ChatOptions.defaults() : options;
        String model = resolveModel(opts.model());
        List<Map<String, String>> wire = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            wire.add(Map.of("role", m.role(), "content", m.content()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", opts.temperature());
        body.put("max_tokens", opts.maxTokens() > 0 ? opts.maxTokens() : 900);
        body.put("stream", true);
        body.put("messages", wire);

        StringBuilder text = new StringBuilder();
        AtomicReference<TokenUsage> usage = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        Disposable subscription = client.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .timeout(STREAM_IDLE_TIMEOUT)
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> {
                            if (cancel.isCancelled()) {
                                return;
                            }
                            try {
                                String delta = readEvent(event.data(), usage);
                                if (delta != null && !delta.isEmpty()) {
                                    text.append(delta);
                                    onToken.accept(delta);
                                }
                            } catch (RuntimeException e) {
                                // the consumer failed (e.g. the client went away): stop the upstream call
                                failure.compareAndSet(null, e);
                                cancel.cancel();
                            }
                        },
                        err -> {
                            failure.compareAndSet(null, err);
                            finished.countDown();
                        },
                        finished::countDown);
        cancel.onCancel(() -> {
            subscription.dispose();
            finished.countDown();
        });

        try {
            if (!finished.await(STREAM_MAX_SECONDS, TimeUnit.SECONDS)) {
                subscription.dispose();
                throw new LlmException("Groq stream timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            cancel.cancel();
        }

        if (cancel.isCancelled()) {
            return new StreamResult(text.toString(), usage.get(), model, true);
        }
        Throwable error = failure.get();
        if (error instanceof WebClientResponseException e) {
            throw LlmException.http(e.getStatusCode().value(), retryAfter(e), e);
        }
        if (error != null) {
            throw new LlmException("Groq stream failed: " + error.getClass().getSimpleName(), error);
        }
        if (text.toString().isBlank()) {
            throw new LlmException("Groq returned an empty completion");
        }
        return new StreamResult(text.toString(), usage.get(), model, false);
    }

    /** Seconds from a numeric Retry-After header, or null (HTTP-date values are ignored). */
    private static Integer retryAfter(WebClientResponseException e) {
        String raw = e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (raw == null) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(raw.strip());
            return seconds > 0 && seconds < 86_400 ? (int) Math.ceil(seconds) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String resolveModel(String requested) {
        if (requested != null && config.allowedModels().contains(requested)) {
            return requested;
        }
        return config.defaultModel();
    }

    /** Parses one SSE data payload; returns the content delta (possibly null) and captures usage when present. */
    private static String readEvent(String data, AtomicReference<TokenUsage> usage) {
        if (data == null || data.isBlank() || "[DONE]".equals(data.strip())) {
            return null;
        }
        JsonNode node;
        try {
            node = JSON.readTree(data);
        } catch (Exception e) {
            return null; // ignore malformed keep-alive fragments
        }
        JsonNode u = node.has("usage") && !node.get("usage").isNull() ? node.get("usage") : node.path("x_groq").path("usage");
        if (u.isObject() && u.has("prompt_tokens")) {
            usage.set(TokenUsage.of(u.path("prompt_tokens").asInt(), u.path("completion_tokens").asInt(), false));
        }
        JsonNode content = node.path("choices").path(0).path("delta").path("content");
        return content.isTextual() ? content.asText() : null;
    }}
