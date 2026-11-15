package com.bookbuddy.jobs;

import com.bookbuddy.quota.LlmBudgetService.Priority;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A durable background task. Survives restarts; refused or failed work is retried with {@code notBefore}. */
@Document("jobs_queue")
@CompoundIndex(name = "due", def = "{'status': 1, 'notBefore': 1, 'priority': 1}")
@CompoundIndex(name = "user_created", def = "{'userId': 1, 'createdAt': -1}")
@Getter
@Setter
@NoArgsConstructor
public class JobQueueDocument {

    public enum Type {
        MEMORY_EXTRACTION(Priority.EXTRACTION),
        MEMORY_REBUILD(Priority.CONSOLIDATION),
        MEMORY_CONSOLIDATION(Priority.CONSOLIDATION);

        private final Priority priority;

        Type(Priority priority) {
            this.priority = priority;
        }

        public Priority priority() {
            return priority;
        }
    }

    public enum Status {
        PENDING,
        RUNNING,
        DONE,
        FAILED
    }

    @Id
    private String id;

    private Type type;
    private String userId;
    private Map<String, String> payload = new HashMap<>();

    /** At most one unfinished job per key. */
    private String dedupeKey;

    private Priority priority;

    /** {@code priority.ordinal()}: lower runs first (safety &gt; chat &gt; summary &gt; extraction &gt; consolidation). */
    private int priorityRank;

    private Status status = Status.PENDING;
    private int attempts;
    private int maxAttempts = 3;

    /** Not eligible before this instant. */
    private Instant notBefore;

    /** A RUNNING job whose lock expired is treated as abandoned (the server died) and is picked up again. */
    private Instant lockedUntil;

    /** Error class and message only: never user content. */
    private String lastError;

    private String result;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant finishedAt;
}
