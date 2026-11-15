package com.bookbuddy.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MessageRepository extends MongoRepository<MessageDocument, String> {

    Optional<MessageDocument> findByIdAndConversationIdAndUserId(String id, String conversationId, String userId);

    /** Newest first. */
    List<MessageDocument> findByConversationIdAndUserIdOrderByCreatedAtDescIdDesc(
            String conversationId, String userId, Limit limit);

    List<MessageDocument> findByConversationIdAndUserIdAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
            String conversationId, String userId, Instant createdAt, Limit limit);

    List<MessageDocument> findByConversationIdAndUserIdAndCreatedAtAndIdLessThanOrderByIdDesc(
            String conversationId, String userId, Instant createdAt, String id, Limit limit);

    long countByConversationId(String conversationId);

    void deleteByConversationId(String conversationId);

    /** Whole conversation in reading order (data export). */
    List<MessageDocument> findByConversationIdAndUserIdOrderByCreatedAtAscIdAsc(String conversationId, String userId);

    long deleteByUserId(String userId);

    long countByUserId(String userId);
}