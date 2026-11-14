package com.bookbuddy.embedding;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.bookbuddy.config.BookBuddyProperties;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * E5 embedder backed by Microsoft ONNX Runtime; active when {@code bookbuddy.embedding.enabled=true}.
 *
 * <p>Startup validates the model file. A real forward pass additionally needs the multilingual-E5 SentencePiece
 * tokenizer, which is not implemented yet, so for now this provider always delegates to {@link HashEmbeddingProvider}
 * (with a warning) and leaves {@link EmbeddingStatus#isLoaded()} {@code false}. Once inference is implemented,
 * {@link #loadModel()} is the single place that should flip the status.
 */
@Service
@Primary
@ConditionalOnProperty(name = "bookbuddy.embedding.enabled", havingValue = "true")
public class OnnxEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingProvider.class);

    private final BookBuddyProperties.Embedding config;
    private final EmbeddingStatus status;
    private final HashEmbeddingProvider fallback = new HashEmbeddingProvider();

    private volatile boolean modelActive = false;

    public OnnxEmbeddingProvider(BookBuddyProperties properties, EmbeddingStatus status) {
        this.config = properties.embedding();
        this.status = status;
    }

    @PostConstruct
    void loadModel() {
        status.setLoaded(false);
        status.setActiveModel(fallback.modelName());

        Path model = Path.of(config.modelPath());
        if (!Files.isRegularFile(model)) {
            log.warn("Embedding model not found at {}; using hash fallback embeddings", model.toAbsolutePath());
            return;
        }
        Path tokenizer = Path.of(config.tokenizerPath());
        if (!Files.isRegularFile(tokenizer)) {
            log.warn("Tokenizer not found at {}; using hash fallback embeddings", tokenizer.toAbsolutePath());
            return;
        }

        OrtEnvironment env;
        try {
            env = OrtEnvironment.getEnvironment(); // process-wide singleton; deliberately not closed
        } catch (Throwable t) {
            log.warn("ONNX runtime unavailable: {}; using hash fallback embeddings", t.toString());
            return;
        }
        try (OrtSession session = env.createSession(model.toString(), new OrtSession.SessionOptions())) {
            log.info("ONNX model {} is loadable (inputs: {})", model, session.getInputNames());
        } catch (Throwable t) { // includes UnsatisfiedLinkError from the native runtime
            log.warn("Could not load ONNX model {}: {}; using hash fallback embeddings", model, t.toString());
            return;
        }

        log.warn("ONNX model is present but E5 tokenization/inference is not implemented yet; "
                + "using hash fallback embeddings and keeping EmbeddingStatus.loaded=false");
    }

    @Override
    public float[] embedPassage(String text) {
        return modelActive ? runModel(PASSAGE_PREFIX + text) : fallback.embedPassage(text);
    }

    @Override
    public float[] embedQuery(String text) {
        return modelActive ? runModel(QUERY_PREFIX + text) : fallback.embedQuery(text);
    }

    @Override
    public boolean isReady() {
        return modelActive || fallback.isReady();
    }

    @Override
    public int dimension() {
        return modelActive && config.dimension() > 0 ? config.dimension() : HashEmbeddingProvider.DIMENSION;
    }

    @Override
    public String modelName() {
        return modelActive ? "multilingual-e5-small" : fallback.modelName();
    }

    @Override
    public String version() {
        return modelActive ? "e5-small-" + dimension() : fallback.version();
    }

    @Override
    public boolean isSemantic() {
        return modelActive;
    }

    private float[] runModel(String prefixedText) {
        // Unreachable until tokenization is implemented (modelActive is never set to true).
        throw new UnsupportedOperationException("ONNX inference not implemented");
    }
}
