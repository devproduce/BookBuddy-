package com.bookbuddy.account;

import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.book.BookDtos.BookResponse;
import com.bookbuddy.conversation.ConversationDocument.BookScope;
import com.bookbuddy.conversation.ConversationDtos.MessageResponse;
import com.bookbuddy.memory.MemoryDtos.MemoryResponse;
import java.time.Instant;
import java.util.List;

public final class AccountDtos {

    private AccountDtos() {}

    /** Everything Book Buddy holds about a user, except secrets (password hash) and derived vectors. */
    public record ExportDocument(
            int version,
            Instant exportedAt,
            Account account,
            List<BookResponse> books,
            List<ExportConversation> conversations,
            List<MemoryResponse> memories) {}

    public record Account(
            String id,
            String email,
            String displayName,
            boolean memoryEnabled,
            String defaultLanguage,
            UserDocument.Consent consent,
            List<UserDocument.CrisisContact> crisisContacts,
            Instant createdAt) {}

    public record ExportConversation(
            String id,
            String title,
            boolean incognito,
            BookScope bookScope,
            String summary,
            Instant createdAt,
            Instant updatedAt,
            List<MessageResponse> messages) {}

    /** How much was removed by {@code DELETE /account/data}. */
    public record DeletionReport(long books, long conversations, long messages, long memories) {}
}
