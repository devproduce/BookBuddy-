package com.bookbuddy.ingestion;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface IngestionJobRepository extends MongoRepository<IngestionJobDocument, String> {

    Optional<IngestionJobDocument> findFirstByBookIdAndUserIdOrderByCreatedAtDesc(String bookId, String userId);

    long deleteByBookId(String bookId);

    long deleteByUserId(String userId);
}
