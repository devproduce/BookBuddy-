package com.bookbuddy.vector;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface BookChunkRepository extends MongoRepository<BookChunkDocument, String> {

    /** Paged chunk listing without the (large) embedding field. */
    @Query(value = "{ 'bookId': ?0, 'userId': ?1 }", fields = "{ 'embedding': 0 }")
    Page<BookChunkDocument> findPageByBookIdAndUserId(String bookId, String userId, Pageable pageable);

    long countByBookId(String bookId);

    long deleteByBookId(String bookId);
}
