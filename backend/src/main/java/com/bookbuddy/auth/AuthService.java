package com.bookbuddy.auth;

import com.bookbuddy.auth.AuthDtos.AuthResponse;
import com.bookbuddy.auth.AuthDtos.LoginRequest;
import com.bookbuddy.auth.AuthDtos.RegisterRequest;
import com.bookbuddy.auth.AuthDtos.UserResponse;
import com.bookbuddy.common.ApiException;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String DEFAULT_CONSENT_VERSION = "v1";
    private static final String DEFAULT_LANGUAGE = "en";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw emailTaken();
        }

        UserDocument user = new UserDocument();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setConsent(new UserDocument.Consent(
                request.consentVersion() == null || request.consentVersion().isBlank()
                        ? DEFAULT_CONSENT_VERSION
                        : request.consentVersion(),
                Instant.now()));
        user.setMemoryEnabled(false);
        user.setDefaultLanguage(
                request.defaultLanguage() == null || request.defaultLanguage().isBlank()
                        ? DEFAULT_LANGUAGE
                        : request.defaultLanguage());

        UserDocument saved;
        try {
            saved = userRepository.save(user);
        } catch (DuplicateKeyException e) {
            throw emailTaken();
        }
        return toAuthResponse(saved);
    }

    public AuthResponse login(LoginRequest request) {
        String email = normalize(request.email());
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (AuthenticationServiceException e) {
            throw e;
        } catch (AuthenticationException e) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    "Invalid email or password");
        }
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        UserDocument user = userRepository
                .findById(principal.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));
        return toAuthResponse(user);
    }

    public UserResponse me(String userId) {
        return userRepository
                .findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> ApiException.unauthorized("User no longer exists"));
    }

    private AuthResponse toAuthResponse(UserDocument user) {
        String token = jwtService.createToken(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getDisplayName());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException emailTaken() {
        return ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
    }
}
