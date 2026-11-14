package com.bookbuddy.settings;

import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.auth.UserRepository;
import com.bookbuddy.common.ApiException;
import com.bookbuddy.safety.CrisisResponseService;
import com.bookbuddy.settings.SettingsDtos.AcceptConsentRequest;
import com.bookbuddy.settings.SettingsDtos.CrisisContactInput;
import com.bookbuddy.settings.SettingsDtos.SettingsResponse;
import com.bookbuddy.settings.SettingsDtos.UpdateSettingsRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final Set<String> LANGUAGES = Set.of("en", "hi", "es", "fr", "de");

    private final UserRepository users;
    private final CrisisResponseService crisis;

    public SettingsResponse get(String userId) {
        return response(load(userId));
    }

    public SettingsResponse update(String userId, UpdateSettingsRequest request) {
        UserDocument user = load(userId);

        if (request.consent() != null) {
            if (request.consent().accepted()) {
                accept(user, request.consent().version());
            } else {
                user.setConsent(null); // withdrawing consent blocks chat again
            }
        }
        if (request.memoryEnabled() != null) {
            user.setMemoryEnabled(request.memoryEnabled());
        }
        if (request.defaultLanguage() != null) {
            String lang = request.defaultLanguage().strip().toLowerCase();
            if (!LANGUAGES.contains(lang)) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Unsupported language");
            }
            user.setDefaultLanguage(lang);
        }
        if (request.crisisContacts() != null) {
            List<UserDocument.CrisisContact> contacts = new ArrayList<>();
            for (CrisisContactInput c : request.crisisContacts()) {
                contacts.add(new UserDocument.CrisisContact(c.name().strip(), c.phone().strip()));
            }
            user.setCrisisContacts(contacts);
        }
        return response(users.save(user));
    }

    public SettingsResponse acceptConsent(String userId, AcceptConsentRequest request) {
        UserDocument user = load(userId);
        accept(user, request == null ? null : request.version());
        return response(users.save(user));
    }

    private SettingsResponse response(UserDocument user) {
        // the built-in helplines only: the user's own contacts are already in crisisContacts
        return SettingsResponse.from(user, crisis.resources(null));
    }

    private static void accept(UserDocument user, String version) {
        String v = version == null || version.isBlank() ? ConsentPolicy.CURRENT_VERSION : version.strip();
        user.setConsent(new UserDocument.Consent(v, Instant.now()));
    }

    private UserDocument load(String userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.unauthorized("User no longer exists"));
    }
}