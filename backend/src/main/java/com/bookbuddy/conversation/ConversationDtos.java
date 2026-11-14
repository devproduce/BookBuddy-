package com.bookbuddy.conversation;

import com.bookbuddy.ai.TokenUsage;
import com.bookbuddy.conversation.ConversationDocument.BookScope;
import com.bookbuddy.conversation.ConversationDocument.ScopeMode;
import com.bookbuddy.conversation.MessageDocument.InputMode;
import com.bookbuddy.conversation.MessageDocument.Role;
import com.bookbuddy.conversation.MessageDocument.Status;
import com.bookbuddy.safety.RiskLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ConversationDtos {

    private ConversationDtos() {}

    public record BookScopeInput(ScopeMode mode, @Size(max = 20) List<String> bookIds) {}

    public record CreateConversationRequest(
            @Size(max = 120) String title, Boolean incognito, @Valid BookScopeInput bookScope) {}

    /** Every field is optional; only the ones present are changed. */
    public record UpdateConversationRequest(
            @Size(max = 120) String title, Boolean incognito, @Valid BookScopeInput bookScope) {}

    public record ConversationResponse(
            String id,
            String title,
            boolean incognito,
            BookScope bookScope,
            int messageCount,
            boolean hasSummary,
            Instant createdAt,
            Instant updatedAt) {

        public static ConversationResponse from(ConversationDocument c) {
            return new ConversationResponse(
                    c.getId(),
                    c.getTitle(),
                    c.isIncognito(),
                    c.getBookScope() == null ? BookScope.all() : c.getBookScope(),
                    c.getMessageCount(),
                    c.getSummary() != null && !c.getSummary().isBlank(),
                    c.getCreatedAt(),
                    c.getUpdatedAt());
        }
    }

    public record MessageResponse(
            String id,
            String conversationId,
            Role role,
            String content,
            Status status,
            List<MessageCitation> citations,
            List<String> memoryIdsUsed,
            String modelId,
            String promptVersion,
            TokenUsage tokenUsage,
            RiskLevel safetyLevel,
            String language,
            InputMode inputMode,
            Instant createdAt) {

        public static MessageResponse from(MessageDocument m) {
            return new MessageResponse(
                    m.getId(),
                    m.getConversationId(),
                    m.getRole(),
                    m.getContent(),
                    m.getStatus(),
                    m.getCitations() == null ? List.of() : m.getCitations(),
                    m.getMemoryIdsUsed() == null ? List.of() : m.getMemoryIdsUsed(),
                    m.getModelId(),
                    m.getPromptVersion(),
                    m.getTokenUsage(),
                    m.getSafetyLevel(),
                    m.getLanguage(),
                    m.getInputMode(),
                    m.getCreatedAt());
        }
    }

    public record SendMessageRequest(
            @NotBlank @Size(max = 4000) String content, @Size(max = 16) String language, InputMode inputMode) {}

    public record SendMessageResponse(MessageResponse userMessage, MessageResponse assistantMessage) {}

    /** {@code nextCursor} is null on the last page. */
    public record CursorPage<T>(List<T> items, String nextCursor) {}
}