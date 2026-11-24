package com.bookbuddy.memory;

import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Status;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MemoryRepository extends MongoRepository<MemoryDocument, String> {

    Optional<MemoryDocument> findByIdAndUserId(String id, String userId);

    List<MemoryDocument> findByUserIdOrderByUpdatedAtDesc(String userId);

    List<MemoryDocument> findByUserIdAndStatusOrderByUpdatedAtDesc(String userId, Status status);

    List<MemoryDocument> findByUserIdAndCategoryOrderByUpdatedAtDesc(String userId, Category category);

    List<MemoryDocument> findByUserIdAndCategoryAndStatusOrderByUpdatedAtDesc(
            String userId, Category category, Status status);

    List<MemoryDocument> findByUserIdAndIdIn(String userId, Collection<String> ids);

    /** Active memories whose expiry has passed (pinned ones have no {@code expiresAt}). */
    List<MemoryDocument> findByStatusAndExpiresAtLessThan(Status status, Instant before);

    List<MemoryDocument> findByUserIdAndSourceConversationIdsContaining(String userId, String sourceConversationId);

    long countByUserIdAndStatus(String userId, Status status);

    long deleteByUserId(String userId);
}
