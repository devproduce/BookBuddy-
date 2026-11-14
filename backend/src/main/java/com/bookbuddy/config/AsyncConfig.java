package com.bookbuddy.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor used for background book ingestion. {@code @EnableAsync} is declared on the application class.
 */
@Configuration
public class AsyncConfig {

    public static final String INGESTION_EXECUTOR = "ingestionExecutor";
    public static final String CHAT_EXECUTOR = "chatExecutor";

    @Bean(name = INGESTION_EXECUTOR)
    public ThreadPoolTaskExecutor ingestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /**
     * Runs streaming chat turns and background conversation summaries. Bounded so a burst of streams degrades to
     * "try again shortly" instead of exhausting the server; rejected work surfaces as a RejectedExecutionException.
     */
    @Bean(name = CHAT_EXECUTOR)
    public ThreadPoolTaskExecutor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("chat-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}