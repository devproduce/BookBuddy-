package com.bookbuddy.quota;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface LlmUsageRepository extends MongoRepository<LlmUsageDocument, String> {

    long countByDay(String day);

    long countByDayAndPurpose(String day, LlmBudgetService.Priority purpose);

    long deleteByUserId(String userId);
}
