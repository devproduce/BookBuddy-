package com.bookbuddy.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.auth.UserRepository;
import com.bookbuddy.common.ApiException;
import com.bookbuddy.config.BookBuddyProperties;
import com.bookbuddy.safety.CrisisResponseService;
import com.bookbuddy.settings.SettingsDtos.AcceptConsentRequest;
import com.bookbuddy.settings.SettingsDtos.ConsentUpdate;
import com.bookbuddy.settings.SettingsDtos.CrisisContactInput;
import com.bookbuddy.settings.SettingsDtos.SettingsResponse;
import com.bookbuddy.settings.SettingsDtos.UpdateSettingsRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SettingsServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SettingsService service = new SettingsService(
            users,
            new CrisisResponseService(new BookBuddyProperties(null, null, null, null, null, null,
                    new BookBuddyProperties.Safety(List.of(new BookBuddyProperties.CrisisResource("KIRAN", "1800-599-0019"))),
                    null, null)));
    private UserDocument user;

    @BeforeEach
    void setUp() {
        user = new UserDocument();
        user.setId("u1");
        user.setDefaultLanguage("en");
        when(users.findById("u1")).thenReturn(Optional.of(user));
        when(users.save(any(UserDocument.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void acceptingConsentUnblocksChat() {
        assertFalse(ConsentPolicy.hasAccepted(user));
        assertThrows(ApiException.class, () -> ConsentPolicy.require(user));

        SettingsResponse r = service.acceptConsent("u1", null);

        assertTrue(r.consent().accepted());
        assertEquals("KIRAN", r.crisisResources().get(0).name());
        assertEquals("v1", r.consent().version());
        assertNotNull(r.consent().acceptedAt());
        ConsentPolicy.require(user);

        service.acceptConsent("u1", new AcceptConsentRequest("v2"));
        assertEquals("v2", user.getConsent().version());
    }

    @Test
    void consentRequiredErrorIs403WithStableCode() {
        ApiException ex = assertThrows(ApiException.class, () -> ConsentPolicy.require(user));
        assertEquals(403, ex.getStatus().value());
        assertEquals("CONSENT_REQUIRED", ex.getCode());
    }

    @Test
    void withdrawingConsentBlocksChatAgain() {
        user.setConsent(new UserDocument.Consent("v1", Instant.now()));
        service.update("u1", new UpdateSettingsRequest(new ConsentUpdate(false, null), null, null, null));
        assertFalse(ConsentPolicy.hasAccepted(user));
    }

    @Test
    void updatesOnlyTheFieldsThatArePresent() {
        user.setMemoryEnabled(false);
        user.setCrisisContacts(List.of(new UserDocument.CrisisContact("Old", "112")));

        SettingsResponse r = service.update("u1", new UpdateSettingsRequest(null, true, "hi", null));

        assertTrue(r.memoryEnabled());
        assertEquals("hi", r.defaultLanguage());
        assertEquals(1, r.crisisContacts().size());
        assertEquals("Old", r.crisisContacts().get(0).name());
    }

    @Test
    void replacesCrisisContactsAndRejectsUnknownLanguages() {
        SettingsResponse r = service.update("u1", new UpdateSettingsRequest(null, null, null,
                List.of(new CrisisContactInput(" Asha ", " +91 98765 43210 "))));
        assertEquals("Asha", r.crisisContacts().get(0).name());
        assertEquals("+91 98765 43210", r.crisisContacts().get(0).phone());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.update("u1", new UpdateSettingsRequest(null, null, "xx", null)));
        assertEquals(400, ex.getStatus().value());
    }
}