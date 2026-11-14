package com.bookbuddy.ai;

/** Token accounting for one completion. {@code estimated} is true when the provider reported no usage. */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens, boolean estimated) {

    public static TokenUsage of(int promptTokens, int completionTokens, boolean estimated) {
        return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens, estimated);
    }
}
