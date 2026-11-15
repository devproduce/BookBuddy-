package com.bookbuddy.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code bookbuddy.memory.*}. Missing or non-positive values fall back to the defaults, so a bare
 * {@code new MemoryProperties(0, ...)} (or {@link #defaults()}) is always usable.
 *
 * @param extractEveryMessages  queue an extraction once this many new messages have piled up in a conversation
 * @param idleMinutes           ...or once a conversation has been quiet this long
 * @param emotionalContextDays  lifetime of an unpinned EMOTIONAL_CONTEXT memory
 * @param coreProfileTokens     token budget of the always-included core profile
 * @param memoryTokens          token budget of the similarity-ranked memories
 * @param maxMemoriesPerPrompt  upper bound on ranked memories in one prompt
 * @param maxCandidatesPerRun   upper bound on candidates accepted from one extraction call
 * @param minSimilarity         ranked memories must be at least this similar to the message (semantic embeddings)
 * @param duplicateSimilarity   merge threshold: at or above this a candidate is a duplicate (semantic embeddings)
 * @param mergeSimilarity       merge threshold: at or above this a candidate refers to an existing memory
 * @param recencyHalfLifeDays   a memory loses half of its recency weight after this many days
 * @param maxRebuildCalls       LLM calls a single rebuild may make
 * @param consolidateAbove      queue a consolidation when a user has more active memories than this
 */
@ConfigurationProperties("bookbuddy.memory")
public record MemoryProperties(
        int extractEveryMessages,
        int idleMinutes,
        int emotionalContextDays,
        int coreProfileTokens,
        int memoryTokens,
        int maxMemoriesPerPrompt,
        int maxCandidatesPerRun,
        double minSimilarity,
        double duplicateSimilarity,
        double mergeSimilarity,
        int recencyHalfLifeDays,
        int maxRebuildCalls,
        int consolidateAbove) {

    public MemoryProperties {
        extractEveryMessages = extractEveryMessages > 0 ? extractEveryMessages : 6;
        idleMinutes = idleMinutes > 0 ? idleMinutes : 10;
        emotionalContextDays = emotionalContextDays > 0 ? emotionalContextDays : 14;
        coreProfileTokens = coreProfileTokens > 0 ? coreProfileTokens : 150;
        memoryTokens = memoryTokens > 0 ? memoryTokens : 350;
        maxMemoriesPerPrompt = maxMemoriesPerPrompt > 0 ? maxMemoriesPerPrompt : 8;
        maxCandidatesPerRun = maxCandidatesPerRun > 0 ? maxCandidatesPerRun : 8;
        minSimilarity = minSimilarity > 0 ? minSimilarity : 0.35;
        duplicateSimilarity = duplicateSimilarity > 0 ? duplicateSimilarity : 0.90;
        mergeSimilarity = mergeSimilarity > 0 ? mergeSimilarity : 0.75;
        recencyHalfLifeDays = recencyHalfLifeDays > 0 ? recencyHalfLifeDays : 45;
        maxRebuildCalls = maxRebuildCalls > 0 ? maxRebuildCalls : 20;
        consolidateAbove = consolidateAbove > 0 ? consolidateAbove : 60;
    }

    public static MemoryProperties defaults() {
        return new MemoryProperties(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
