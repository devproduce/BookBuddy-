package com.bookbuddy.book;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("books")
@CompoundIndex(name = "user_contentHash_unique", def = "{'userId': 1, 'contentHash': 1}", unique = true)
@Getter
@Setter
@NoArgsConstructor
public class BookDocument {

    public enum Status {
        QUEUED,
        EXTRACTING,
        CHUNKING,
        EMBEDDING,
        COMPLETED,
        FAILED,
        NEEDS_OCR
    }

    @Id
    private String id;

    private String userId;
    private String title;
    private String author;
    private Integer pageCount;
    private String language;
    private Double languageConfidence;
    private String contentHash;
    private Long fileSizeBytes;
    private Status status = Status.QUEUED;
    private Double extractionQuality;
    private String failureReason;
    private int chunkCount;
    private String chunkingVersion;
    private String embeddingVersion;

    /** Informational only; never exposed as a download link. */
    private String fileUri;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
