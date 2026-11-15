package com.bookbuddy.memory;

import java.util.List;

/**
 * Memories chosen for one prompt.
 *
 * @param promptBlock     text to append to the system prompt ("" when nothing applies)
 * @param memoryIds       ids of every memory included, core profile first (stored on the assistant message)
 * @param estimatedTokens rough size of {@code promptBlock}
 */
public record MemoryContext(String promptBlock, List<String> memoryIds, int estimatedTokens) {

    public static final MemoryContext EMPTY = new MemoryContext("", List.of(), 0);

    public MemoryContext {
        promptBlock = promptBlock == null ? "" : promptBlock;
        memoryIds = memoryIds == null ? List.of() : List.copyOf(memoryIds);
    }

    public boolean isEmpty() {
        return memoryIds.isEmpty();
    }
}
