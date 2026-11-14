package com.bookbuddy.book;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BookRepository extends MongoRepository<BookDocument, String> {

    List<BookDocument> findByUserIdOrderByCreatedAtDesc(String userId);

    List<BookDocument> findByUserId(String userId);

    Optional<BookDocument> findByIdAndUserId(String id, String userId);

    Optional<BookDocument> findByUserIdAndContentHash(String userId, String contentHash);
}
