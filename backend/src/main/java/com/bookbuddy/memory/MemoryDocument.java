package com.bookbuddy.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One thing Book Buddy remembers about a reader. Editable, explainable and deletable by the user. */
@Document("user_memories")
@CompoundIndex(name = "user_status", def = "{'userId': 1, 'status': 1, 'updatedAt': -1}")
@CompoundIndex(name = "status_expires", def = "{'status': 1, 'expiresAt': 1}")
@Getter
@Setter
@NoArgsConstructor
public class MemoryDocument {

    public enum Category {
        PERSONAL_BACKGROUND,
        INTEREST,
        PREFERENCE,
        COMMUNICATION_STYLE,
        PERSONALITY_OBSERVATION,
        THINKING_PATTERN,
        GOAL,
        VALUE,
        EXPERIENCE,
        RECURRING_CHALLENGE,
        EMOTIONAL_CONTEXT,
        RELATIONSHIP_CONTEXT,
        READING_PREFERENCE,
        LEARNING_PREFERENCE,
        ACHIEVEMENT,
        CURRENT_ACTIVITY
    }

    /** EXPLICIT: user said it. INFERRED: model guessed. USER_CONFIRMED: inferred then confirmed. */
    public enum SourceType {
        EXPLICIT,
        INFERRED,
        USER_CONFIRMED
    }

    public enum Stability {
        STABLE,
        EVOLVING,
        TEMPORARY
    }

    public enum Sensitivity {
        NORMAL,
        SENSITIVE
    }

    public enum Status {
        ACTIVE,
        ARCHIVED,
        EXPIRED,
        DELETED
    }

    @Id
    private String id;

    private String userId;
    private Category category;
    private String content;

    private List<String> sourceConversationIds = new ArrayList<>();
    private List<String> sourceMessageIds = new ArrayList<>();
    private List<String> evidence = new ArrayList<>();

    private SourceType sourceType = SourceType.EXPLICIT;

    /** 0..1. INFERRED memories never exceed {@link MemoryPolicy#INFERRED_CONFIDENCE_CAP} until confirmed. */
    private double confidence = 1.0;

    /** 0..1: how useful this is to bring up. */
    private double importance = 0.5;

    /** How often the pattern has appeared (conversation references). */
    private int recurrence;

    private Stability stability = Stability.EVOLVING;

    private List<Double> embedding = new ArrayList<>();
    private String embeddingModel;

    private Sensitivity sensitivity = Sensitivity.NORMAL;

    /** High-impact inferences awaiting a soft confirmation on the Memory Page. */
    private boolean needsConfirmation;

    private boolean pinned;

    /** Id of the memory that replaced this one; set together with {@code status = ARCHIVED}. */
    private String supersededBy;

    private Status status = Status.ACTIVE;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastUsedAt;

    public boolean isSensitive() {
        return sensitivity == Sensitivity.SENSITIVE;
    }

    /** True once the user has stated or confirmed it: such a memory is a "stated" fact in prompts. */
    public boolean isStated() {
        return sourceType == SourceType.EXPLICIT || sourceType == SourceType.USER_CONFIRMED;
    }

    public boolean isUserConfirmed() {
        return sourceType == SourceType.USER_CONFIRMED;
    }
}
