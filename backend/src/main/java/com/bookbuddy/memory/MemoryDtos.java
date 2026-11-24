package com.bookbuddy.memory;

import com.bookbuddy.jobs.JobQueueDocument;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Sensitivity;
import com.bookbuddy.memory.MemoryDocument.SourceType;
import com.bookbuddy.memory.MemoryDocument.Stability;
import com.bookbuddy.memory.MemoryDocument.Status;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class MemoryDtos {

    private MemoryDtos() {}

    /** Embeddings are never sent to clients. {@code status} is the effective one: an overdue memory reads EXPIRED. */
    public record MemoryResponse(
            String id,
            Category category,
            String content,
            SourceType sourceType,
            double confidence,
            double importance,
            int recurrence,
            Stability stability,
            Sensitivity sensitivity,
            boolean needsConfirmation,
            boolean pinned,
            Status status,
            String supersededBy,
            List<String> sourceConversationIds,
            List<String> evidence,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt,
            Instant lastUsedAt) {

        public static MemoryResponse from(MemoryDocument m, Instant now) {
            Status effective = m.getStatus() == Status.ACTIVE && MemoryPolicy.isExpired(m, now)
                    ? Status.EXPIRED
                    : m.getStatus();
            return new MemoryResponse(
                    m.getId(),
                    m.getCategory(),
                    m.getContent(),
                    m.getSourceType(),
                    m.getConfidence(),
                    m.getImportance(),
                    m.getRecurrence(),
                    m.getStability() == null ? Stability.EVOLVING : m.getStability(),
                    m.getSensitivity(),
                    m.isNeedsConfirmation(),
                    m.isPinned(),
                    effective,
                    m.getSupersededBy(),
                    m.getSourceConversationIds() == null ? List.of() : List.copyOf(m.getSourceConversationIds()),
                    m.getEvidence() == null ? List.of() : List.copyOf(m.getEvidence()),
                    m.getExpiresAt(),
                    m.getCreatedAt(),
                    m.getUpdatedAt(),
                    m.getLastUsedAt());
        }
    }

    public record CreateMemoryRequest(
            @NotNull Category category, @NotBlank @Size(max = 300) String content, Boolean pinned) {}

    /** Every field is optional; only the ones present are changed. Editing the content marks it USER_CONFIRMED. */
    public record UpdateMemoryRequest(
            Category category, @Size(min = 1, max = 300) String content, Boolean pinned, Boolean sensitive) {}

    public record ProfileResponse(
            String text,
            String profileSummary,
            int estimatedTokens,
            int tokenBudget,
            List<MemoryResponse> items,
            InteractionContextDto interactionContext) {}

    public record InteractionContextDto(
            String preferredTone,
            String preferredDetailLevel,
            List<String> communicationStyle,
            List<String> emotionalSupportPreferences,
            List<String> advicePreferences,
            List<String> personalizationGuidelines,
            String followUpQuestionPreference) {

        public static InteractionContextDto from(UserProfileDocument.InteractionContext c) {
            if (c == null) {
                return empty();
            }
            return new InteractionContextDto(
                    nullToEmpty(c.getPreferredTone()),
                    nullToEmpty(c.getPreferredDetailLevel()),
                    copy(c.getCommunicationStyle()),
                    copy(c.getEmotionalSupportPreferences()),
                    copy(c.getAdvicePreferences()),
                    copy(c.getPersonalizationGuidelines()),
                    nullToEmpty(c.getFollowUpQuestionPreference()));
        }

        public static InteractionContextDto empty() {
            return new InteractionContextDto("", "", List.of(), List.of(), List.of(), List.of(), "");
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }

        private static List<String> copy(List<String> list) {
            return list == null ? List.of() : List.copyOf(list);
        }
    }

    public record JobResponse(
            String jobId,
            String type,
            String status,
            int attempts,
            String lastError,
            String result,
            Instant notBefore,
            Instant createdAt,
            Instant finishedAt) {

        public static JobResponse from(JobQueueDocument j) {
            return new JobResponse(
                    j.getId(), j.getType().name(), j.getStatus().name(), j.getAttempts(), j.getLastError(),
                    j.getResult(), j.getNotBefore(), j.getCreatedAt(), j.getFinishedAt());
        }
    }
}
