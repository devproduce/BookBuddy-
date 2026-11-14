package com.bookbuddy.embedding;

import org.springframework.stereotype.Component;

/**
 * Tracks whether a real embedding model (ONNX) has been loaded. Defaults to false; the deterministic hash fallback
 * never flips it, so {@code loaded == false} means "running on the dev fallback".
 */
@Component
public class EmbeddingStatus {

    private volatile boolean loaded = false;
    private volatile String activeModel = HashEmbeddingProvider.MODEL_NAME;

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    /** Name of the model whose vectors are actually being produced right now. */
    public String getActiveModel() {
        return activeModel;
    }

    public void setActiveModel(String activeModel) {
        this.activeModel = activeModel;
    }
}
