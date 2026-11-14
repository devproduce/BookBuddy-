package com.bookbuddy.vector;

import com.bookbuddy.document.SectionType;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("book_chunks")
@CompoundIndexes({
    @CompoundIndex(name = "book_chunk_unique", def = "{'bookId': 1, 'chunkIndex': 1}", unique = true),
    @CompoundIndex(name = "user_book", def = "{'userId': 1, 'bookId': 1}")
})
@Getter
@Setter
@NoArgsConstructor
public class BookChunkDocument {

    @Id
    private String id;

    private String userId;
    private String bookId;
    private int chunkIndex;
    private String content;
    private Integer pageStart;
    private Integer pageEnd;
    private String chapterTitle;
    private String language;
    private int tokenCount;
    private String chunkingVersion;
    private String embeddingVersion;
    private String embeddingModel;
    private SectionType sectionType = SectionType.BODY;
    private List<Double> embedding;
    private Instant createdAt;
}
