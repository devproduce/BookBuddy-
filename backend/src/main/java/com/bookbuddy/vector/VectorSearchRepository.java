package com.bookbuddy.vector;

import java.util.Collection;
import java.util.List;

public interface VectorSearchRepository {

    /**
     * Nearest chunks by cosine similarity, best first.
     *
     * @param userId owner; results are always restricted to this user
     * @param bookIds optional restriction to specific books (null/empty = all of the user's books)
     */
    List<ScoredChunk> search(String userId, float[] queryVector, Collection<String> bookIds, int topN);
}
