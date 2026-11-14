package com.bookbuddy.vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Hybrid retrieval: vector search + keyword search merged with Reciprocal Rank Fusion. */
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    /** Standard RRF damping constant. */
    static final int RRF_K = 60;

    private final VectorSearchRepository vectorSearch;
    private final KeywordSearchRepository keywordSearch;

    public record HybridHit(BookChunkDocument chunk, double score, Double vectorScore, boolean keywordMatch) {}

    /**
     * @param minVectorSimilarity vector hits below this cosine are ignored (pass 0 to disable, e.g. for the hash
     *     embedder whose similarities are not comparable to E5)
     */
    public List<HybridHit> search(
            String userId,
            String queryText,
            float[] queryVector,
            Collection<String> bookIds,
            int topN,
            double minVectorSimilarity) {
        int candidates = Math.max(topN * 3, 20);
        List<ScoredChunk> vectorHits = vectorSearch.search(userId, queryVector, bookIds, candidates).stream()
                .filter(h -> h.score() >= minVectorSimilarity)
                .toList();
        List<ScoredChunk> keywordHits = keywordSearch.search(userId, queryText, bookIds, candidates);
        return fuse(vectorHits, keywordHits, topN);
    }

    static List<HybridHit> fuse(List<ScoredChunk> vectorHits, List<ScoredChunk> keywordHits, int topN) {
        Map<String, Double> rrf = new LinkedHashMap<>();
        Map<String, BookChunkDocument> chunks = new LinkedHashMap<>();
        Map<String, Double> vectorScores = new LinkedHashMap<>();
        Map<String, Boolean> keyword = new LinkedHashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            ScoredChunk h = vectorHits.get(i);
            String key = keyOf(h.chunk());
            rrf.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
            chunks.putIfAbsent(key, h.chunk());
            vectorScores.put(key, h.score());
        }
        for (int i = 0; i < keywordHits.size(); i++) {
            ScoredChunk h = keywordHits.get(i);
            String key = keyOf(h.chunk());
            rrf.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
            chunks.putIfAbsent(key, h.chunk());
            keyword.put(key, true);
        }

        List<HybridHit> hits = new ArrayList<>();
        rrf.forEach((key, score) ->
                hits.add(new HybridHit(chunks.get(key), score, vectorScores.get(key), keyword.getOrDefault(key, false))));
        hits.sort(Comparator.comparingDouble(HybridHit::score).reversed());
        return hits.size() > topN ? new ArrayList<>(hits.subList(0, topN)) : hits;
    }

    private static String keyOf(BookChunkDocument c) {
        return c.getId() != null ? c.getId() : c.getBookId() + "#" + c.getChunkIndex();
    }
}
