package com.bookbuddy.quota;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One LLM call. Never stores prompt or completion text: only accounting and observability data. */
@Document("llm_usage")
@CompoundIndex(name = "day_purpose", def = "{'day': 1, 'purpose': 1}")
@CompoundIndex(name = "user_created", def = "{'userId': 1, 'createdAt': -1}")
@Getter
@Setter
@NoArgsConstructor
public class LlmUsageDocument {

    public enum Outcome {
        OK,
        ERROR
    }

    @Id
    private String id;

    private String userId;
    private LlmBudgetService.Priority purpose;
    private String model;
    private int promptTokens;
    private int completionTokens;
    private long latencyMs;
    private Outcome outcome = Outcome.OK;

    /** UTC day (yyyy-MM-dd): the free quota resets at 00:00 UTC. */
    private String day;

    private Instant createdAt;
}
