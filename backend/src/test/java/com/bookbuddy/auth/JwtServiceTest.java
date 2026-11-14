package com.bookbuddy.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bookbuddy.config.BookBuddyProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-123456";

    static BookBuddyProperties props(String secret, long expirationMs) {
        return new BookBuddyProperties(
                new BookBuddyProperties.Cors(List.of("http://localhost:5173")),
                new BookBuddyProperties.Jwt(secret, expirationMs),
                null, null, null, null, null, null, null);
    }

    @Test
    void createsAndParsesToken() {
        JwtService service = new JwtService(props(SECRET, 60_000));
        String token = service.createToken("user-1", "a@b.com");

        Claims claims = service.parse(token);
        assertEquals("user-1", claims.getSubject());
        assertEquals("a@b.com", claims.get("email", String.class));
        assertTrue(service.isValid(token));
        assertEquals("user-1", service.extractUserId(token));
        assertEquals("a@b.com", service.extractEmail(token));
    }

    @Test
    void rejectsTamperedAndExpiredTokens() {
        JwtService service = new JwtService(props(SECRET, 60_000));
        String token = service.createToken("user-1", "a@b.com");
        assertFalse(service.isValid(token + "x"));
        assertFalse(service.isValid("garbage"));

        JwtService expired = new JwtService(props(SECRET, -1_000));
        String old = expired.createToken("user-1", "a@b.com");
        assertThrows(JwtException.class, () -> expired.parse(old));

        JwtService other = new JwtService(props("another-secret-another-secret-another-1234", 60_000));
        assertFalse(other.isValid(token));
    }

    @Test
    void rejectsShortSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtService(props("short", 1000)));
    }
}
