package com.bookbuddy.memory;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserProfileRepository extends MongoRepository<UserProfileDocument, String> {

    Optional<UserProfileDocument> findByUserId(String userId);

    long deleteByUserId(String userId);
}
