package com.bookbuddy.ai;

/**
 * Per-call generation options. A {@code null} model means "the provider default"; models outside the configured
 * allow-list are ignored.
 */
public record ChatOptions(String model, double temperature, int maxTokens) {

    public static ChatOptions defaults() {
        return new ChatOptions(null, 0.4, 900);
    }
}
