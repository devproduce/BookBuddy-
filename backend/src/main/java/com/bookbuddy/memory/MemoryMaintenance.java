package com.bookbuddy.memory;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly housekeeping: marks overdue memories (e.g. EMOTIONAL_CONTEXT after 14 days) as EXPIRED. */
@Component
public class MemoryMaintenance {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenance.class);

    private final MemoryService memory;
    private final boolean enabled;

    public MemoryMaintenance(MemoryService memory, @Value("${bookbuddy.jobs.enabled:true}") boolean enabled) {
        this.memory = memory;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
    void expire() {
        if (!enabled) {
            return;
        }
        try {
            int n = memory.expireDue(Instant.now());
            if (n > 0) {
                log.info("Expired {} memories", n);
            }
        } catch (RuntimeException e) {
            log.warn("Memory expiry failed: {}", e.getClass().getSimpleName());
        }
    }
}
