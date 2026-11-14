package com.bookbuddy.ai;

/**
 * Outcome of a streamed completion.
 *
 * @param text the full text delivered to the token consumer
 * @param usage nullable; null when the provider did not report usage
 * @param modelId nullable model that served the request
 * @param cancelled true when the stream stopped early because of a {@link CancelToken}
 */
public record StreamResult(String text, TokenUsage usage, String modelId, boolean cancelled) {}
