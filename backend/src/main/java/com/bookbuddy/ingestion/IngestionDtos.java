package com.bookbuddy.ingestion;

import com.bookbuddy.book.BookDocument;
import com.bookbuddy.vector.BookChunkDocument;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public final class IngestionDtos {

    private IngestionDtos() {}

    public record BookStatusResponse(
            String bookId,
            BookDocument.Status status,
            IngestionJobDocument.Stage stage,
            int progress,
            String message,
            String failureReason,
            int chunkCount,
            Integer pageCount,
            String jobId,
            boolean fileUploaded,
            Instant updatedAt) {}

    public record ChunkResponse(
            String id,
            int chunkIndex,
            String content,
            Integer pageStart,
            Integer pageEnd,
            String chapterTitle,
            String language,
            int tokenCount,
            String sectionType) {

        public static ChunkResponse from(BookChunkDocument c) {
            return new ChunkResponse(
                    c.getId(),
                    c.getChunkIndex(),
                    c.getContent(),
                    c.getPageStart(),
                    c.getPageEnd(),
                    c.getChapterTitle(),
                    c.getLanguage(),
                    c.getTokenCount(),
                    c.getSectionType() == null ? null : c.getSectionType().name());
        }
    }

    public record ChunkPageResponse(
            List<ChunkResponse> items, int page, int size, int totalPages, long totalElements) {

        public static ChunkPageResponse from(Page<BookChunkDocument> p) {
            return new ChunkPageResponse(
                    p.getContent().stream().map(ChunkResponse::from).toList(),
                    p.getNumber(),
                    p.getSize(),
                    p.getTotalPages(),
                    p.getTotalElements());
        }
    }
}
