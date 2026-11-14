package com.bookbuddy.common;

/**
 * Uniform error body returned by all API endpoints.
 *
 * @param retryAfterSeconds nullable hint for clients when {@code retryable} is true
 */
public record ApiError(String code, String message, boolean retryable, Integer retryAfterSeconds) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, false, null);
    }
}
