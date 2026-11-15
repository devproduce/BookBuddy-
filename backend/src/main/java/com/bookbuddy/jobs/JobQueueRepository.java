package com.bookbuddy.jobs;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface JobQueueRepository extends MongoRepository<JobQueueDocument, String> {

    Optional<JobQueueDocument> findByIdAndUserId(String id, String userId);

    Optional<JobQueueDocument> findFirstByDedupeKeyAndStatusIn(String dedupeKey, Collection<JobQueueDocument.Status> statuses);

    long deleteByUserId(String userId);
}
