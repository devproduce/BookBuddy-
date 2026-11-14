package com.bookbuddy.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HashEmbeddingProviderTest {

    private final HashEmbeddingProvider provider = new HashEmbeddingProvider();

    @Test
    void producesNormalised384DimVectors() {
        assertEquals(384, provider.dimension());
        assertTrue(provider.isReady());
        for (String text : new String[] {"The quick brown fox", "यह एक हिंदी वाक्य है", "", "a", "!!!"}) {
            float[] p = provider.embedPassage(text);
            float[] q = provider.embedQuery(text);
            assertEquals(384, p.length);
            assertEquals(384, q.length);
            assertEquals(1.0, VectorMath.norm(p), 1e-4, "passage norm for '" + text + "'");
            assertEquals(1.0, VectorMath.norm(q), 1e-4, "query norm for '" + text + "'");
        }
    }

    @Test
    void isDeterministic() {
        assertArrayEquals(provider.embedPassage("same text here"), provider.embedPassage("same text here"));
        assertArrayEquals(provider.embedQuery("same text here"), provider.embedQuery("same text here"));
    }

    @Test
    void sharedVocabularyIsMoreSimilarThanUnrelatedText() {
        float[] query = provider.embedQuery("who lived in the lighthouse");
        float[] related = provider.embedPassage("The keeper lived alone in the old lighthouse for years.");
        float[] unrelated = provider.embedPassage("Quarterly revenue grew because exports of machinery increased.");
        assertTrue(VectorMath.cosine(query, related) > VectorMath.cosine(query, unrelated) + 0.1);
    }

    @Test
    void passageAndQueryAgreeOnSameContent() {
        float[] p = provider.embedPassage("lighthouse keeper");
        float[] q = provider.embedQuery("lighthouse keeper");
        assertEquals(1.0, VectorMath.cosine(p, q), 1e-4);
    }

    @Test
    void emptyTextsOfDifferentRoleStillDiffer() {
        float[] p = provider.embedPassage("");
        float[] q = provider.embedQuery("");
        assertTrue(VectorMath.cosine(p, q) < 0.99);
    }

    @Test
    void exposesPrefixesAndMetadata() {
        assertEquals("passage: ", EmbeddingProvider.PASSAGE_PREFIX);
        assertEquals("query: ", EmbeddingProvider.QUERY_PREFIX);
        assertEquals("hash-v1-384", provider.version());
        assertEquals(false, provider.isSemantic());
    }
}
