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
        ABOUT_ME,
        INTERESTS,
        PREFERENCES,
        GOALS,
        EXPERIENCES,
        OBSERVED_PATTERNS,
        EMOTIONAL_CONTEXT
    }

    /** EXPLICIT: the user said it. INFERRED: Book Buddy guessed it; stays unconfirmed until the user confirms. */
    public enum Classification {
        EXPLICIT,
        INFERRED
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

    private String sourceConversationId;
    private List<String> sourceMessageIds = new ArrayList<>();

    private Classification classification = Classification.EXPLICIT;

    /** 0..1. INFERRED memories never exceed {@link MemoryPolicy#INFERRED_CONFIDENCE_CAP} until confirmed. */
    private double confidence = 1.0;

    /** 0..1: how useful this is to bring up. */
    private double importance = 0.5;

    private List<Double> embedding = new ArrayList<>();
    private String embeddingModel;

    private Sensitivity sensitivity = Sensitivity.NORMAL;
    private boolean userVerified;
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
        return classification == Classification.EXPLICIT || userVerified;
    }
}
