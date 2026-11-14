package com.bookbuddy.ingestion;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("ingestion_jobs")
@CompoundIndex(name = "book_created", def = "{'bookId': 1, 'createdAt': -1}")
@Getter
@Setter
@NoArgsConstructor
public class IngestionJobDocument {

    public enum Stage {
        QUEUED,
        EXTRACTING,
        CHUNKING,
        EMBEDDING,
        COMPLETED,
        FAILED,
        NEEDS_OCR,
        CANCELLED;

        /** Still being (or about to be) worked on. */
        public boolean isActive() {
            return this == QUEUED || this == EXTRACTING || this == CHUNKING || this == EMBEDDING;
        }
    }

    @Id
    private String id;

    private String userId;
    private String bookId;
    private Stage stage = Stage.QUEUED;

    /** 0..100 */
    private int progress;

    private String message;
    private String failureReason;
    private int attempt = 1;
    private boolean cancelRequested;
    private Integer pagesTotal;
    private Integer chunksTotal;
    private Integer chunksEmbedded;
    private Instant startedAt;
    private Instant finishedAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
