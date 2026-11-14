package com.bookbuddy.auth;

import com.bookbuddy.common.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {}

    /** Returns the authenticated user's id or throws a 401 {@link ApiException}. */
    public static String userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getUserId();
        }
        throw ApiException.unauthorized("Authentication required");
    }
}
