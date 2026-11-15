package com.bookbuddy.memory;

import com.bookbuddy.jobs.JobQueueDocument;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Classification;
import com.bookbuddy.memory.MemoryDocument.Sensitivity;
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
            Classification classification,
            double confidence,
            double importance,
            Sensitivity sensitivity,
            boolean userVerified,
            boolean pinned,
            Status status,
            String supersededBy,
            String sourceConversationId,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt,
            Instant lastUsedAt) {

        public static MemoryResponse from(MemoryDocument m, Instant now) {
            Status effective = m.getStatus() == Status.ACTIVE && MemoryPolicy.isExpired(m, now)
                    ? Status.EXPIRED
                    : m.getStatus();
            return new MemoryResponse(
                    m.getId(), m.getCategory(), m.getContent(), m.getClassification(), m.getConfidence(),
                    m.getImportance(), m.getSensitivity(), m.isUserVerified(), m.isPinned(), effective,
                    m.getSupersededBy(), m.getSourceConversationId(), m.getExpiresAt(), m.getCreatedAt(),
                    m.getUpdatedAt(), m.getLastUsedAt());
        }
    }

    public record CreateMemoryRequest(
            @NotNull Category category, @NotBlank @Size(max = 300) String content, Boolean pinned) {}

    /** Every field is optional; only the ones present are changed. Editing the content marks it user-verified. */
    public record UpdateMemoryRequest(
            Category category, @Size(min = 1, max = 300) String content, Boolean pinned, Boolean sensitive) {}

    public record ProfileResponse(String text, int estimatedTokens, int tokenBudget, List<MemoryResponse> items) {}

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
