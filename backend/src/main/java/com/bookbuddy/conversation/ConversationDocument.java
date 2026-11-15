package com.bookbuddy.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("conversations")
@CompoundIndex(name = "user_updated", def = "{'userId': 1, 'updatedAt': -1, '_id': -1}")
@Getter
@Setter
@NoArgsConstructor
public class ConversationDocument {

    public enum ScopeMode {
        /** Retrieve from every book in the user's library. */
        ALL,
        /** Retrieve only from {@link BookScope#bookIds()}. */
        ONE,
        /** Never retrieve: a pure companion chat. */
        NONE
    }

    public record BookScope(ScopeMode mode, List<String> bookIds) {
        public BookScope {
            mode = mode == null ? ScopeMode.ALL : mode;
            bookIds = bookIds == null ? List.of() : List.copyOf(bookIds);
        }

        public static BookScope all() {
            return new BookScope(ScopeMode.ALL, List.of());
        }
    }

    @Id
    private String id;

    private String userId;
    private String title;

    /** Incognito conversations never feed memory extraction or cross-conversation summaries. */
    private boolean incognito;

    private BookScope bookScope = BookScope.all();
    private int messageCount;

    /** Id of the newest message already folded into {@link #summary}; null if nothing is summarised yet. */
    private String lastSummarizedMessageId;

    private String summary;

    /** Newest message already mined for memories; messages after this instant are still to be looked at. */
    private Instant lastMemoryExtractedAt;

    /** {@code messageCount} at the last memory extraction (or skip), to tell how much is new. */
    private int lastExtractedMessageCount;

    private Instant createdAt;
    private Instant updatedAt;

    /** Never null even for legacy documents. */
    public List<String> scopedBookIds() {
        return bookScope == null || bookScope.mode() != ScopeMode.ONE ? new ArrayList<>() : new ArrayList<>(bookScope.bookIds());
    }
}