package com.bookbuddy.vector;

import com.bookbuddy.config.BookBuddyProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.stereotype.Component;

/**
 * Preferred vector search: MongoDB Atlas {@code $vectorSearch}. Any failure (no Atlas, missing index, empty result
 * while the index is still building) transparently falls back to {@link BruteForceVectorSearchRepository}. After a
 * failure Atlas is not retried for a few minutes so a plain MongoDB does not pay a failed round-trip per query.
 * {@code bookbuddy.vector.mode=memory} skips Atlas entirely.
 *
 * <p>The Atlas index must declare {@code userId} and {@code bookId} as {@code filter} fields.
 */
@Component
@Primary
public class AtlasVectorSearchRepository implements VectorSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(AtlasVectorSearchRepository.class);
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(5);

    private final MongoTemplate mongoTemplate;
    private final BookBuddyProperties.Vector config;
    private final BruteForceVectorSearchRepository fallback;

    private volatile Instant atlasDisabledUntil = Instant.MIN;

    public AtlasVectorSearchRepository(
            MongoTemplate mongoTemplate, BookBuddyProperties properties, BruteForceVectorSearchRepository fallback) {
        this.mongoTemplate = mongoTemplate;
        this.config = properties.vector();
        this.fallback = fallback;
    }

    @Override
    public List<ScoredChunk> search(String userId, float[] queryVector, Collection<String> bookIds, int topN) {
        if (config.memoryOnly() || Instant.now().isBefore(atlasDisabledUntil)) {
            return fallback.search(userId, queryVector, bookIds, topN);
        }
        try {
            List<ScoredChunk> hits = atlasSearch(userId, queryVector, bookIds, topN);
            if (!hits.isEmpty()) {
                return hits;
            }
            log.debug("$vectorSearch returned no hits; verifying with brute-force search");
        } catch (Exception e) {
            atlasDisabledUntil = Instant.now().plus(RETRY_AFTER_FAILURE);
            log.warn("$vectorSearch unavailable ({}); using brute-force cosine for the next {} minutes",
                    e.getMessage(), RETRY_AFTER_FAILURE.toMinutes());
        }
        return fallback.search(userId, queryVector, bookIds, topN);
    }

    List<ScoredChunk> atlasSearch(String userId, float[] queryVector, Collection<String> bookIds, int topN) {
        List<Double> vector = new ArrayList<>(queryVector.length);
        for (float f : queryVector) {
            vector.add((double) f);
        }
        Document filter = new Document("userId", userId);
        if (bookIds != null && !bookIds.isEmpty()) {
            filter.append("bookId", new Document("$in", new ArrayList<>(bookIds)));
        }
        Document vectorSearch = new Document("$vectorSearch", new Document()
                .append("index", config.indexName())
                .append("path", "embedding")
                .append("queryVector", vector)
                .append("numCandidates", Math.max(topN * 20, 100))
                .append("limit", topN)
                .append("filter", filter));

        AggregationOperation search = context -> vectorSearch;
        AggregationOperation score =
                context -> new Document("$addFields", new Document("score", new Document("$meta", "vectorSearchScore")));
        AggregationOperation noEmbedding = context -> new Document("$project", new Document("embedding", 0));

        List<Document> raw = mongoTemplate
                .aggregate(Aggregation.newAggregation(search, score, noEmbedding), "book_chunks", Document.class)
                .getMappedResults();

        List<ScoredChunk> out = new ArrayList<>(raw.size());
        for (Document doc : raw) {
            Number s = (Number) doc.get("score");
            BookChunkDocument chunk = mongoTemplate.getConverter().read(BookChunkDocument.class, doc);
            out.add(new ScoredChunk(chunk, s == null ? 0 : s.doubleValue()));
        }
        return out;
    }
}
