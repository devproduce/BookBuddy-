package com.bookbuddy.safety;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Audit trail for safety escalations. Deliberately logs the level and a timestamp only: never the message text, the
 * user id, or the conversation id.
 */
@Component
public class SafetyEventLogger {

    private static final Logger log = LoggerFactory.getLogger("com.bookbuddy.safety.events");

    public void log(RiskLevel level) {
        if (level == null || level == RiskLevel.NONE) {
            return;
        }
        log.warn("safety_event level={} at={}", level, Instant.now());
    }
}
