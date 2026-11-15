package com.bookbuddy.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ConversationRepository extends MongoRepository<ConversationDocument, String> {

    Optional<ConversationDocument> findByIdAndUserId(String id, String userId);

    /** Newest first. */
    List<ConversationDocument> findByUserIdOrderByUpdatedAtDescIdDesc(String userId, Limit limit);

    /** Older than the cursor's timestamp. */
    List<ConversationDocument> findByUserIdAndUpdatedAtLessThanOrderByUpdatedAtDescIdDesc(
            String userId, Instant updatedAt, Limit limit);

    /** Same timestamp as the cursor but a smaller id (tie-break). */
    List<ConversationDocument> findByUserIdAndUpdatedAtAndIdLessThanOrderByIdDesc(
            String userId, Instant updatedAt, String id, Limit limit);

    long deleteByIdAndUserId(String id, String userId);

    /** Every conversation of a user (data export / account deletion). */
    List<ConversationDocument> findByUserId(String userId);

    long deleteByUserId(String userId);

    /** Non-incognito conversations last touched within {@code [from, to]}: candidates for idle memory extraction. */
    List<ConversationDocument> findByIncognitoFalseAndUpdatedAtBetween(Instant from, Instant to, Limit limit);
}