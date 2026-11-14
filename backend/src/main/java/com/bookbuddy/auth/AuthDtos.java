package com.bookbuddy.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Size(max = 100) String displayName,
            @AssertTrue(message = "Consent must be accepted") boolean consentAccepted,
            @Size(max = 32) String consentVersion,
            @Size(max = 16) String defaultLanguage) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {}

    public record AuthResponse(String token, String userId, String email, String displayName) {}

    public record UserResponse(
            String id,
            String email,
            String displayName,
            String consentVersion,
            Instant consentAcceptedAt,
            boolean memoryEnabled,
            String defaultLanguage,
            List<UserDocument.CrisisContact> crisisContacts,
            Instant createdAt,
            Instant updatedAt) {

        public static UserResponse from(UserDocument u) {
            UserDocument.Consent c = u.getConsent();
            return new UserResponse(
                    u.getId(),
                    u.getEmail(),
                    u.getDisplayName(),
                    c == null ? null : c.version(),
                    c == null ? null : c.acceptedAt(),
                    u.isMemoryEnabled(),
                    u.getDefaultLanguage(),
                    u.getCrisisContacts() == null ? List.of() : List.copyOf(u.getCrisisContacts()),
                    u.getCreatedAt(),
                    u.getUpdatedAt());
        }
    }
}
