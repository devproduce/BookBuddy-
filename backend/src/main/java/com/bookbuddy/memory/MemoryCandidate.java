package com.bookbuddy.memory;

import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Classification;
import java.util.List;

/**
 * A memory proposed by the extraction LLM, after schema validation.
 *
 * @param evidence          the user's own words backing the candidate; required to trust an EXPLICIT claim
 * @param sensitive         the model's flag (OR-ed with the deterministic {@link SensitiveTopics} check)
 * @param replacesPrevious  the user changed their mind / corrected an earlier statement
 * @param sourceMessageIds  messages of the extraction window the candidate came from
 */
public record MemoryCandidate(
        Category category,
        String content,
        Classification classification,
        double confidence,
        double importance,
        boolean sensitive,
        boolean replacesPrevious,
        String evidence,
        List<String> sourceMessageIds) {

    public MemoryCandidate {
        sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
        evidence = evidence == null ? "" : evidence;
    }
}
