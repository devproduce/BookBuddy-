package com.bookbuddy.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.bookbuddy.config.BookBuddyProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * multilingual-e5-small via ONNX Runtime + Hugging Face tokenizer.
 *
 * <p>Active when {@code bookbuddy.embedding.enabled=true}. Expects {@code model.onnx} and {@code tokenizer.json}
 * (see {@code scripts/download-e5-model.ps1}). On load failure, falls back to {@link HashEmbeddingProvider}.
 */
@Service
@Primary
@ConditionalOnProperty(name = "bookbuddy.embedding.enabled", havingValue = "true")
public class OnnxEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingProvider.class);

    private final BookBuddyProperties.Embedding config;
    private final EmbeddingStatus status;
    private final HashEmbeddingProvider fallback = new HashEmbeddingProvider();

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private String inputIdsName = "input_ids";
    private String attentionMaskName = "attention_mask";
    private boolean wantsTokenTypeIds;
    private String outputName;
    private volatile boolean modelActive;
    private int modelDimension = 384;

    public OnnxEmbeddingProvider(BookBuddyProperties properties, EmbeddingStatus status) {
        this.config = properties.embedding();
        this.status = status;
    }

    @PostConstruct
    void loadModel() {
        status.setLoaded(false);
        status.setActiveModel(fallback.modelName());

        Path model = Path.of(config.modelPath()).toAbsolutePath().normalize();
        Path tokenizerPath = Path.of(config.tokenizerPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(model)) {
            log.warn("Embedding model not found at {}; using hash fallback. Run scripts/download-e5-model.ps1", model);
            return;
        }
        if (!Files.isRegularFile(tokenizerPath)) {
            log.warn("Tokenizer not found at {}; using hash fallback. Run scripts/download-e5-model.ps1", tokenizerPath);
            return;
        }

        try {
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = env.createSession(model.toString(), opts);

            Set<String> inputs = session.getInputNames();
            if (inputs == null || inputs.isEmpty()) {
                throw new IllegalStateException("ONNX model has no inputs");
            }
            inputIdsName = inputs.contains("input_ids") ? "input_ids" : inputs.iterator().next();
            attentionMaskName = inputs.contains("attention_mask") ? "attention_mask" : inputIdsName;
            wantsTokenTypeIds = inputs.contains("token_type_ids");

            Set<String> outputs = session.getOutputNames();
            if (outputs == null || outputs.isEmpty()) {
                throw new IllegalStateException("ONNX model has no outputs");
            }
            if (outputs.contains("last_hidden_state")) {
                outputName = "last_hidden_state";
            } else if (outputs.contains("sentence_embedding")) {
                outputName = "sentence_embedding";
            } else {
                outputName = outputs.iterator().next();
            }

            float[] probe = runModel(QUERY_PREFIX + "hello");
            modelDimension = probe.length;
            modelActive = true;
            status.setLoaded(true);
            status.setActiveModel(modelName());
            log.info(
                    "Loaded multilingual-e5-small ONNX from {} (inputs={}, output={}, dim={})",
                    model,
                    inputs,
                    outputName,
                    modelDimension);
        } catch (IOException | OrtException | RuntimeException | LinkageError t) {
            closeQuietly();
            modelActive = false;
            status.setLoaded(false);
            status.setActiveModel(fallback.modelName());
            log.warn("Could not initialise ONNX embeddings ({}). Using hash fallback.", t.toString(), t);
        }
    }

    @PreDestroy
    void shutdown() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
                // ignore
            }
            session = null;
        }
        if (tokenizer != null) {
            try {
                tokenizer.close();
            } catch (Exception ignored) {
                // ignore
            }
            tokenizer = null;
        }
        env = null;
    }

    @Override
    public float[] embedPassage(String text) {
        return embed(true, nullToEmpty(text));
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(false, nullToEmpty(text));
    }

    private float[] embed(boolean passage, String text) {
        if (!modelActive) {
            return passage ? fallback.embedPassage(text) : fallback.embedQuery(text);
        }
        String prefixed = (passage ? PASSAGE_PREFIX : QUERY_PREFIX) + text;
        try {
            return runModel(prefixed);
        } catch (RuntimeException e) {
            log.warn("ONNX embed failed ({}); using hash fallback for this call", e.getMessage());
            return passage ? fallback.embedPassage(text) : fallback.embedQuery(text);
        }
    }

    private float[] runModel(String prefixedText) {
        if (session == null || tokenizer == null || env == null) {
            throw new IllegalStateException("ONNX session not loaded");
        }
        int max = config.maxTokens() > 0 ? config.maxTokens() : 512;
        Encoding encoding = tokenizer.encode(prefixedText);
        long[] ids = truncate(encoding.getIds(), max);
        long[] mask = truncate(encoding.getAttentionMask(), max);
        if (mask.length != ids.length) {
            mask = new long[ids.length];
            java.util.Arrays.fill(mask, 1L);
        }

        List<OnnxTensor> owned = new ArrayList<>();
        try {
            long[] shape = new long[] {1L, ids.length};
            OnnxTensor idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
            owned.add(idsTensor);
            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put(inputIdsName, idsTensor);

            if (!attentionMaskName.equals(inputIdsName) && session.getInputNames().contains(attentionMaskName)) {
                OnnxTensor maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape);
                owned.add(maskTensor);
                feeds.put(attentionMaskName, maskTensor);
            }
            if (wantsTokenTypeIds) {
                long[] types = new long[ids.length];
                OnnxTensor typeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape);
                owned.add(typeTensor);
                feeds.put("token_type_ids", typeTensor);
            }

            OrtSession.Result result = session.run(feeds);
            try {
                return poolAndNormalize(result, mask);
            } finally {
                result.close();
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed: " + e.getMessage(), e);
        } finally {
            for (OnnxTensor t : owned) {
                try {
                    t.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private float[] poolAndNormalize(OrtSession.Result result, long[] attentionMask) throws OrtException {
        OnnxValue raw = resolveOutput(result);
        if (!(raw instanceof OnnxTensor)) {
            throw new IllegalStateException("Unexpected ONNX output value: " + raw.getClass().getName());
        }
        OnnxTensor out = (OnnxTensor) raw;
        Object value = out.getValue();

        if (value instanceof float[]) {
            float[] flat = (float[]) value;
            return VectorMath.l2Normalize(flat.clone());
        }
        if (value instanceof float[][]) {
            float[][] matrix = (float[][]) value;
            // [1][dim] sentence embedding, or [seq][hidden] without batch
            if (matrix.length == 1 && matrix[0].length != attentionMask.length) {
                return VectorMath.l2Normalize(matrix[0].clone());
            }
            if (matrix.length == attentionMask.length || matrix[0].length > 8) {
                return meanPool(matrix, attentionMask);
            }
            return VectorMath.l2Normalize(matrix[0].clone());
        }
        if (value instanceof float[][][]) {
            float[][][] hidden = (float[][][]) value;
            return meanPool(hidden[0], attentionMask);
        }
        throw new IllegalStateException("Unexpected ONNX output type: " + value.getClass().getName());
    }

    private OnnxValue resolveOutput(OrtSession.Result result) {
        Optional<OnnxValue> named = result.get(outputName);
        if (named.isPresent()) {
            return named.get();
        }
        if (result.size() == 0) {
            throw new IllegalStateException("ONNX result is empty");
        }
        return result.get(0);
    }

    private float[] meanPool(float[][] tokenVectors, long[] attentionMask) {
        if (tokenVectors == null || tokenVectors.length == 0 || tokenVectors[0] == null) {
            throw new IllegalStateException("Empty ONNX hidden state");
        }
        int hidden = tokenVectors[0].length;
        float[] sum = new float[hidden];
        float count = 0f;
        int n = Math.min(tokenVectors.length, attentionMask.length);
        for (int i = 0; i < n; i++) {
            if (attentionMask[i] == 0) {
                continue;
            }
            float[] row = tokenVectors[i];
            for (int j = 0; j < hidden; j++) {
                sum[j] += row[j];
            }
            count += 1f;
        }
        if (count > 0f) {
            for (int j = 0; j < hidden; j++) {
                sum[j] /= count;
            }
        }
        return VectorMath.l2Normalize(sum);
    }

    private static long[] truncate(long[] src, int max) {
        if (src == null) {
            return new long[0];
        }
        if (src.length <= max) {
            return src;
        }
        long[] out = new long[max];
        System.arraycopy(src, 0, out, 0, max);
        return out;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    @Override
    public boolean isReady() {
        return modelActive || fallback.isReady();
    }

    @Override
    public int dimension() {
        return modelActive ? modelDimension : HashEmbeddingProvider.DIMENSION;
    }

    @Override
    public String modelName() {
        return modelActive ? "multilingual-e5-small" : fallback.modelName();
    }

    @Override
    public String version() {
        return modelActive ? "e5-small-onnx-v1-" + dimension() : fallback.version();
    }

    @Override
    public boolean isSemantic() {
        return modelActive;
    }
}
