package com.bookbuddy.conversation;

import com.bookbuddy.ai.TokenUsage;
import com.bookbuddy.safety.RiskLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("messages")
@CompoundIndexes({
    @CompoundIndex(name = "conversation_created", def = "{'conversationId': 1, 'createdAt': -1, '_id': -1}"),
    @CompoundIndex(name = "user_created", def = "{'userId': 1, 'createdAt': -1}")
})
@Getter
@Setter
@NoArgsConstructor
public class MessageDocument {

    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM
    }

    public enum Status {
        COMPLETE,
        /** Generation stopped early (client cancelled, connection lost or server restarted); content is partial. */
        INTERRUPTED,
        FAILED
    }

    public enum InputMode {
        TEXT,
        VOICE
    }

    @Id
    private String id;

    private String conversationId;
    private String userId;
    private Role role;
    private String content = "";
    private Status status = Status.COMPLETE;
    private List<MessageCitation> citations = new ArrayList<>();

    /** Ids of memories injected into the prompt (populated once memory ships). */
    private List<String> memoryIdsUsed = new ArrayList<>();

    private String modelId;
    private String promptVersion;
    private TokenUsage tokenUsage;
    private RiskLevel safetyLevel;
    private String language;
    private InputMode inputMode = InputMode.TEXT;
    private Instant createdAt;
}