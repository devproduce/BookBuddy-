package com.bookbuddy.vector;

import com.bookbuddy.embedding.VectorMath;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Always-available vector search: streams the user's candidate chunks from MongoDB and ranks them by cosine
 * similarity in-process. Works on any MongoDB (no Atlas index required); cost is linear in the user's chunk count.
 */
@Component
@RequiredArgsConstructor
public class BruteForceVectorSearchRepository implements VectorSearchRepository {

    private final MongoTemplate mongoTemplate;

    @Override
    public List<ScoredChunk> search(String userId, float[] queryVector, Collection<String> bookIds, int topN) {
        Criteria criteria = Criteria.where("userId").is(userId).and("embedding").exists(true);
        if (bookIds != null && !bookIds.isEmpty()) {
            criteria = criteria.and("bookId").in(bookIds);
        }
        try (Stream<BookChunkDocument> stream = mongoTemplate.stream(Query.query(criteria), BookChunkDocument.class)) {
            return rank(stream, queryVector, topN);
        }
    }

    /** Keeps the {@code topN} best chunks using a bounded min-heap; the embedding is dropped from the results. */
    static List<ScoredChunk> rank(Stream<BookChunkDocument> candidates, float[] queryVector, int topN) {
        if (topN <= 0) {
            return List.of();
        }
        PriorityQueue<ScoredChunk> heap = new PriorityQueue<>(topN + 1, Comparator.comparingDouble(ScoredChunk::score));
        candidates.forEach(chunk -> {
            double score = VectorMath.cosine(queryVector, chunk.getEmbedding());
            if (heap.size() < topN) {
                heap.add(new ScoredChunk(chunk, score));
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.add(new ScoredChunk(chunk, score));
            }
        });
        List<ScoredChunk> result = new ArrayList<>(heap);
        result.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        result.forEach(sc -> sc.chunk().setEmbedding(null));
        return result;
    }
}
