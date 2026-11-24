package com.bookbuddy.memory;

import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.SourceType;
import com.bookbuddy.memory.MemoryDocument.Stability;
import java.util.List;

/**
 * A memory proposed by the memory engine, after schema validation.
 *
 * @param evidence             the user's own words backing the candidate; required to trust an EXPLICIT claim
 * @param sensitive            the model's flag (OR-ed with the deterministic {@link SensitiveTopics} check)
 * @param replacesPrevious     the user changed their mind / corrected an earlier statement
 * @param sourceMessageIds     messages of the extraction window the candidate came from
 * @param sourceConversationIds conversations the candidate came from
 * @param needsConfirmation    high-impact inference that should be soft-confirmed
 */
public record MemoryCandidate(
        Category category,
        String content,
        SourceType sourceType,
        double confidence,
        double importance,
        int recurrence,
        Stability stability,
        boolean sensitive,
        boolean replacesPrevious,
        boolean needsConfirmation,
        List<String> evidence,
        List<String> sourceMessageIds,
        List<String> sourceConversationIds) {

    public MemoryCandidate {
        sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
        sourceConversationIds = sourceConversationIds == null ? List.of() : List.copyOf(sourceConversationIds);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        stability = stability == null ? Stability.EVOLVING : stability;
    }

    /** Primary evidence string for policy checks (first quote, or empty). */
    public String primaryEvidence() {
        return evidence.isEmpty() ? "" : evidence.get(0);
    }
}
