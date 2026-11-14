package com.bookbuddy.ai;

public interface LlmProvider {

    /** False when no API key / endpoint is configured; callers should then use a local fallback. */
    boolean isConfigured();

    /**
     * Single-turn completion.
     *
     * @throws LlmException on transport, HTTP or response-shape errors
     */
    String complete(String systemPrompt, String userPrompt);
}
