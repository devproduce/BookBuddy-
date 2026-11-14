package com.bookbuddy.ai;

import java.util.List;
import java.util.function.Consumer;

public interface LlmProvider {

    /** False when no API key / endpoint is configured; callers should then use a local fallback. */
    boolean isConfigured();

    /**
     * Single-turn completion.
     *
     * @throws LlmException on transport, HTTP or response-shape errors
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * Streams a chat completion, invoking {@code onToken} for every text delta on the calling thread's behalf (the
     * call blocks until the stream ends). Cancelling {@code cancel} stops the upstream request as soon as possible
     * and makes this method return a {@link StreamResult} with {@code cancelled == true}.
     *
     * <p>The default implementation degrades to a single {@link #complete} call delivered as one token, so
     * providers without native streaming still work.
     *
     * @throws LlmException on transport, HTTP or response-shape errors (tokens already delivered stay delivered)
     */
    default StreamResult streamChat(
            List<ChatMessage> messages, ChatOptions options, Consumer<String> onToken, CancelToken cancel) {
        StringBuilder system = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        for (ChatMessage m : messages) {
            if ("system".equals(m.role())) {
                system.append(m.content()).append("\n\n");
            } else {
                rest.append(m.role()).append(": ").append(m.content()).append("\n\n");
            }
        }
        String text = complete(system.toString().strip(), rest.toString().strip());
        if (cancel != null && cancel.isCancelled()) {
            return new StreamResult("", null, null, true);
        }
        onToken.accept(text);
        return new StreamResult(text, null, null, false);
    }

    /** Convenience overload without cancellation. */
    default StreamResult streamChat(List<ChatMessage> messages, ChatOptions options, Consumer<String> onToken) {
        return streamChat(messages, options, onToken, new CancelToken());
    }
}
