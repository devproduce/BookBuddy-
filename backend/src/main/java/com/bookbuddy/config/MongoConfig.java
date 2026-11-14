package com.bookbuddy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/**
 * Enables {@code @CreatedDate} / {@code @LastModifiedDate} support. The {@code MongoTemplate} bean is provided by
 * Spring Boot's Mongo auto-configuration.
 */
@Configuration
@EnableMongoAuditing
public class MongoConfig {}
