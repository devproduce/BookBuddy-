package com.bookbuddy.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bookbuddy.embedding.HashEmbeddingProvider;
import com.bookbuddy.embedding.VectorMath;
import com.bookbuddy.vector.HybridSearchService.HybridHit;
import java.util.List;
import org.junit.jupiter.api.Test;

class VectorSearchTest {

    private final HashEmbeddingProvider embeddings = new HashEmbeddingProvider();

    private BookChunkDocument chunk(String id, String text) {
        BookChunkDocument c = new BookChunkDocument();
        c.setId(id);
        c.setBookId("b1");
        c.setContent(text);
        c.setEmbedding(VectorMath.toList(embeddings.embedPassage(text)));
        return c;
    }

    @Test
    void bruteForceRanksByCosineAndKeepsTopN() {
        List<BookChunkDocument> docs = List.of(
                chunk("a", "The lighthouse keeper lived alone by the sea."),
                chunk("b", "Quarterly revenue grew because exports increased."),
                chunk("c", "A storm hit the lighthouse and the keeper climbed the stairs."),
                chunk("d", "Recipes for bread and butter."));
        List<ScoredChunk> top = BruteForceVectorSearchRepository.rank(
                docs.stream(), embeddings.embedQuery("lighthouse keeper"), 2);

        assertEquals(2, top.size());
        assertTrue(top.get(0).score() >= top.get(1).score());
        assertTrue(List.of("a", "c").contains(top.get(0).chunk().getId()));
        assertTrue(List.of("a", "c").contains(top.get(1).chunk().getId()));
        assertNull(top.get(0).chunk().getEmbedding(), "embeddings are dropped from results");
    }

    @Test
    void keywordRankingPrefersTermMatches() {
        List<BookChunkDocument> docs = List.of(
                chunk("a", "Nothing relevant here at all."),
                chunk("b", "The lighthouse stood tall. The lighthouse keeper waved."),
                chunk("c", "A lighthouse appears once."));
        List<String> keywords = KeywordSearchRepository.keywords("Who is the lighthouse keeper?");
        assertTrue(keywords.contains("lighthouse") && keywords.contains("keeper"));

        List<ScoredChunk> ranked = KeywordSearchRepository.rank(docs, keywords, 5);
        assertEquals(2, ranked.size());
        assertEquals("b", ranked.get(0).chunk().getId());
    }

    @Test
    void reciprocalRankFusionRewardsAgreement() {
        BookChunkDocument a = chunk("a", "x");
        BookChunkDocument b = chunk("b", "y");
        BookChunkDocument c = chunk("c", "z");
        List<ScoredChunk> vector = List.of(new ScoredChunk(a, 0.9), new ScoredChunk(b, 0.8));
        List<ScoredChunk> keyword = List.of(new ScoredChunk(c, 5), new ScoredChunk(b, 3));

        List<HybridHit> fused = HybridSearchService.fuse(vector, keyword, 3);
        assertEquals(3, fused.size());
        assertEquals("b", fused.get(0).chunk().getId(), "b appears in both lists");
        assertEquals(0.8, fused.get(0).vectorScore());
        assertTrue(fused.get(0).keywordMatch());

        assertEquals(1, HybridSearchService.fuse(vector, keyword, 1).size());
    }
}
