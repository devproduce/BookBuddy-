package com.bookbuddy.book;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class BookDtos {

    private BookDtos() {}

    public record CreateBookRequest(
            @NotBlank @Size(max = 300) String title,
            @Size(max = 200) String author,
            @NotBlank @Pattern(regexp = "^[A-Fa-f0-9]{64}$", message = "must be a 64-char hex SHA-256 hash")
                    String contentHash,
            @NotNull @Positive Long fileSizeBytes,
            @Positive Integer pageCount) {}

    public record UpdateBookRequest(
            @Size(min = 1, max = 300) String title, @Size(max = 200) String author) {}

    public record BookResponse(
            String id,
            String title,
            String author,
            Integer pageCount,
            String language,
            Double languageConfidence,
            String contentHash,
            Long fileSizeBytes,
            BookDocument.Status status,
            Double extractionQuality,
            String failureReason,
            int chunkCount,
            String chunkingVersion,
            String embeddingVersion,
            Instant createdAt,
            Instant updatedAt) {

        public static BookResponse from(BookDocument b) {
            return new BookResponse(
                    b.getId(),
                    b.getTitle(),
                    b.getAuthor(),
                    b.getPageCount(),
                    b.getLanguage(),
                    b.getLanguageConfidence(),
                    b.getContentHash(),
                    b.getFileSizeBytes(),
                    b.getStatus(),
                    b.getExtractionQuality(),
                    b.getFailureReason(),
                    b.getChunkCount(),
                    b.getChunkingVersion(),
                    b.getEmbeddingVersion(),
                    b.getCreatedAt(),
                    b.getUpdatedAt());
        }
    }
}
