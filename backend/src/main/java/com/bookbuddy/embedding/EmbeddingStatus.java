package com.bookbuddy.embedding;

import org.springframework.stereotype.Component;

/** Tracks whether the embedding model has been loaded. Defaults to false until an embedder flips it. */
@Component
public class EmbeddingStatus {

    private volatile boolean loaded = false;

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }
}
