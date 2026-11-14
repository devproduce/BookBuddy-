package com.bookbuddy.vector;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Idempotent chunk persistence: upsert keyed by (bookId, chunkIndex). */
@Component
@RequiredArgsConstructor
public class BookChunkWriter {

    private final MongoTemplate mongoTemplate;

    public void upsertAll(List<BookChunkDocument> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        BulkOperations ops = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, BookChunkDocument.class);
        for (BookChunkDocument c : chunks) {
            Query query = Query.query(
                    Criteria.where("bookId").is(c.getBookId()).and("chunkIndex").is(c.getChunkIndex()));
            Update update = new Update()
                    .set("userId", c.getUserId())
                    .set("content", c.getContent())
                    .set("pageStart", c.getPageStart())
                    .set("pageEnd", c.getPageEnd())
                    .set("chapterTitle", c.getChapterTitle())
                    .set("language", c.getLanguage())
                    .set("tokenCount", c.getTokenCount())
                    .set("chunkingVersion", c.getChunkingVersion())
                    .set("embeddingVersion", c.getEmbeddingVersion())
                    .set("embeddingModel", c.getEmbeddingModel())
                    .set("sectionType", c.getSectionType())
                    .set("embedding", c.getEmbedding())
                    .setOnInsert("createdAt", c.getCreatedAt() != null ? c.getCreatedAt() : now);
            ops.upsert(query, update);
        }
        ops.execute();
    }

    /** Removes leftovers from a previous, longer run: every chunk of the book with {@code chunkIndex >= fromIndex}. */
    public long deleteFromIndex(String bookId, int fromIndex) {
        return mongoTemplate
                .remove(Query.query(Criteria.where("bookId").is(bookId).and("chunkIndex").gte(fromIndex)),
                        BookChunkDocument.class)
                .getDeletedCount();
    }

    public long deleteByBook(String bookId) {
        return mongoTemplate
                .remove(Query.query(Criteria.where("bookId").is(bookId)), BookChunkDocument.class)
                .getDeletedCount();
    }
}
