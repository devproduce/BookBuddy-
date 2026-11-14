package com.bookbuddy.settings;

import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.common.ApiException;

/** Single place that decides whether a user may use chat. */
public final class ConsentPolicy {

    public static final String CURRENT_VERSION = "v1";
    public static final String ERROR_CODE = "CONSENT_REQUIRED";

    private ConsentPolicy() {}

    public static boolean hasAccepted(UserDocument user) {
        return user != null && user.getConsent() != null && user.getConsent().acceptedAt() != null;
    }

    /** @throws ApiException 403 {@code CONSENT_REQUIRED} */
    public static void require(UserDocument user) {
        if (!hasAccepted(user)) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    ERROR_CODE,
                    "Please review and accept the privacy and consent notice before chatting.");
        }
    }
}