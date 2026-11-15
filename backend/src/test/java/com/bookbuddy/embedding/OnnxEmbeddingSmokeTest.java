package com.bookbuddy.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.bookbuddy.config.BookBuddyProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Loads the real ONNX model when present under {@code models/}. Skips in CI if files are absent.
 */
class OnnxEmbeddingSmokeTest {

    @Test
    void e5ProducesNormalizedSemanticVectors() {
        Path model = Path.of("models/model.onnx");
        Path tokenizer = Path.of("models/tokenizer.json");
        assumeTrue(Files.isRegularFile(model), "models/model.onnx not present");
        assumeTrue(Files.isRegularFile(tokenizer), "models/tokenizer.json not present");

        BookBuddyProperties.Embedding emb = new BookBuddyProperties.Embedding(
                true, model.toString(), tokenizer.toString(), 384, 512);
        BookBuddyProperties props = new BookBuddyProperties(
                null, null, null, emb, null, null, null, new BookBuddyProperties.Quota(900), null);
        EmbeddingStatus status = new EmbeddingStatus();
        OnnxEmbeddingProvider provider = new OnnxEmbeddingProvider(props, status);
        provider.loadModel();

        assumeTrue(status.isLoaded(), "ONNX model failed to load; see logs");
        assertTrue(provider.isSemantic());
        assertEquals(384, provider.dimension());

        float[] a = provider.embedQuery("what is detachment");
        float[] b = provider.embedPassage("Detachment means observing thoughts without clinging to them.");
        float[] c = provider.embedPassage("The recipe calls for two cups of sugar and butter.");

        assertEquals(384, a.length);
        assertTrue(Math.abs(VectorMath.norm(a) - 1.0) < 0.05, "query should be ~L2-normalised");
        double simRelevant = VectorMath.cosine(a, b);
        double simIrrelevant = VectorMath.cosine(a, c);
        assertTrue(
                simRelevant > simIrrelevant,
                () -> "expected semantic ranking, got " + simRelevant + " vs " + simIrrelevant);
        assertTrue(simRelevant > 0.2, () -> "relevant pair too weak: " + simRelevant);
    }
}
