package com.bookbuddy.conversation;

import com.bookbuddy.ai.TokenUsage;
import com.bookbuddy.conversation.MessageDocument.Status;
import com.bookbuddy.safety.CrisisResponseService.Resource;
import com.bookbuddy.safety.RiskLevel;
import java.util.List;

/**
 * Transport-neutral chat events. {@link ConversationService} produces them; the SSE controller serialises them as
 * {@code meta}, {@code token}, {@code citations}, {@code memory}, {@code safety}, {@code done} and {@code error}
 * events.
 */
public final class ChatEvents {

    private ChatEvents() {}

    public record Meta(String messageId, String userMessageId, String conversationId) {}

    public record Safety(RiskLevel level, List<Resource> resources) {}

    /**
     * Final event. {@code content} is the authoritative text (after quote verification), which can differ from the
     * concatenated tokens when an invented quotation was corrected.
     */
    public record Done(String messageId, Status status, String content, TokenUsage usage, String modelId) {}

    /** {@code retryAfterSeconds} is only set when the provider told us how long to wait (e.g. on RATE_LIMITED). */
    public record Error(String code, String message, boolean retryable, String messageId, Integer retryAfterSeconds) {

        public Error(String code, String message, boolean retryable, String messageId) {
            this(code, message, retryable, messageId, null);
        }
    }

    public interface Sink {

        void meta(Meta meta);

        void token(String text);

        void citations(List<MessageCitation> citations);

        void safety(Safety safety);

        /** Ids of the memories that were part of the prompt ("why this answer"). Optional for sinks. */
        default void memory(List<String> memoryIds) {}

        void done(Done done);

        void error(Error error);

        Sink NOOP = new Sink() {
            @Override
            public void meta(Meta meta) {}

            @Override
            public void token(String text) {}

            @Override
            public void citations(List<MessageCitation> citations) {}

            @Override
            public void safety(Safety safety) {}

            @Override
            public void done(Done done) {}

            @Override
            public void error(Error error) {}
        };
    }
}