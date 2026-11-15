package com.bookbuddy.ai;

public class LlmException extends RuntimeException {

    private final Integer httpStatus;
    private final Integer retryAfterSeconds;

    public LlmException(String message) {
        this(message, null, null, null);
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public LlmException(String message, Throwable cause, Integer httpStatus, Integer retryAfterSeconds) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** An HTTP error answer from the provider; {@code retryAfterSeconds} comes from its Retry-After header, if any. */
    public static LlmException http(int status, Integer retryAfterSeconds, Throwable cause) {
        return new LlmException("Groq request failed with HTTP " + status, cause, status, retryAfterSeconds);
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    /** Seconds the provider asked us to wait, or null when unknown. */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** True when the provider answered 429 (rate limit or quota). */
    public boolean isRateLimited() {
        return httpStatus != null && httpStatus == 429;
    }
}
