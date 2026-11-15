package com.bookbuddy.vector;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface BookChunkRepository extends MongoRepository<BookChunkDocument, String> {

    /** Paged chunk listing without the (large) embedding field. */
    @Query(value = "{ 'bookId': ?0, 'userId': ?1 }", fields = "{ 'embedding': 0 }")
    Page<BookChunkDocument> findPageByBookIdAndUserId(String bookId, String userId, Pageable pageable);

    /** One chunk by position in its book (embedding left out); used to complete truncated passages. */
    @Query(value = "{ 'bookId': ?0, 'userId': ?1, 'chunkIndex': ?2 }", fields = "{ 'embedding': 0 }")
    Optional<BookChunkDocument> findNeighbor(String bookId, String userId, int chunkIndex);

    long countByBookId(String bookId);

    long deleteByBookId(String bookId);

    long deleteByUserId(String userId);
}
