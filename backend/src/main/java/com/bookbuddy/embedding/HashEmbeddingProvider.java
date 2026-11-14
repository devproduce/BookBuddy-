package com.bookbuddy.embedding;

import com.bookbuddy.document.TextTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Deterministic, dependency-free pseudo-embedding for local/dev use when no ONNX model is available.
 *
 * <p>Each word (and adjacent word pair) is hashed with SHA-256 into a signed bucket of a 384-dim vector
 * (feature hashing), so texts sharing vocabulary end up with a positive cosine similarity, which keeps the RAG
 * pipeline meaningful. Text with no usable tokens falls back to a SHA-256 counter-mode expansion of the whole
 * string. Vectors are L2-normalised. This is lexical, <em>not</em> semantic.
 */
@Service
@ConditionalOnProperty(name = "bookbuddy.embedding.enabled", havingValue = "false", matchIfMissing = true)
public class HashEmbeddingProvider implements EmbeddingProvider {

    public static final int DIMENSION = 384;
    public static final String MODEL_NAME = "hash-sha256-fallback";

    @Override
    public float[] embedPassage(String text) {
        return embed(PASSAGE_PREFIX, text);
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(QUERY_PREFIX, text);
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public String version() {
        return "hash-v1-" + DIMENSION;
    }

    /**
     * The E5 role prefix ("passage: " / "query: ") is part of the contract but carries no meaning for a hash
     * embedder, so it only seeds the SHA-256 fallback for empty text and is not tokenized (keeps passage and query\n     * vectors comparable).
     */
    private float[] embed(String prefix, String text) {
        String input = text == null ? "" : text;
        List<String> tokens = TextTokens.contentTokens(input);
        float[] vec = new float[DIMENSION];

        if (tokens.isEmpty()) {
            expandDigest(prefix + input, vec);
            return VectorMath.l2Normalize(vec);
        }

        Map<String, Double> weights = new HashMap<>();
        for (String t : tokens) {
            weights.merge(t, 1.0, Double::sum);
        }
        for (int i = 0; i + 1 < tokens.size(); i++) {
            weights.merge(tokens.get(i) + " " + tokens.get(i + 1), 0.5, Double::sum);
        }

        MessageDigest sha = sha256();
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            double w = 1 + Math.log(e.getValue());
            byte[] d = sha.digest(e.getKey().getBytes(StandardCharsets.UTF_8));
            addFeature(vec, d, 0, w);
            addFeature(vec, d, 8, w);
        }
        return VectorMath.l2Normalize(vec);
    }

    private static void addFeature(float[] vec, byte[] d, int offset, double weight) {
        int idx = ((d[offset] & 0xFF) << 24 | (d[offset + 1] & 0xFF) << 16 | (d[offset + 2] & 0xFF) << 8
                        | (d[offset + 3] & 0xFF))
                & 0x7FFFFFFF;
        idx %= DIMENSION;
        double sign = (d[offset + 4] & 1) == 0 ? 1.0 : -1.0;
        vec[idx] += (float) (sign * weight);
    }

    /** SHA-256 in counter mode: block i = SHA-256(text || i); every 4 bytes become one component in [-1, 1). */
    private static void expandDigest(String text, float[] vec) {
        MessageDigest sha = sha256();
        byte[] base = text.getBytes(StandardCharsets.UTF_8);
        int pos = 0;
        for (int counter = 0; pos < vec.length; counter++) {
            sha.update(base);
            sha.update((byte) (counter >>> 8));
            sha.update((byte) counter);
            byte[] block = sha.digest();
            for (int i = 0; i + 3 < block.length && pos < vec.length; i += 4) {
                int bits = (block[i] & 0xFF) << 24 | (block[i + 1] & 0xFF) << 16 | (block[i + 2] & 0xFF) << 8
                        | (block[i + 3] & 0xFF);
                vec[pos++] = bits / 2147483648.0f;
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
