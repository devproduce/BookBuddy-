package com.bookbuddy.settings;

import com.bookbuddy.auth.CurrentUser;
import com.bookbuddy.settings.SettingsDtos.AcceptConsentRequest;
import com.bookbuddy.settings.SettingsDtos.SettingsResponse;
import com.bookbuddy.settings.SettingsDtos.UpdateSettingsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settings;

    @GetMapping("/settings")
    public SettingsResponse get() {
        return settings.get(CurrentUser.userId());
    }

    @PutMapping("/settings")
    public SettingsResponse put(@Valid @RequestBody UpdateSettingsRequest request) {
        return settings.update(CurrentUser.userId(), request);
    }

    /** Records acceptance of the current privacy / consent notice. The body is optional. */
    @PostMapping("/consent")
    public SettingsResponse acceptConsent(@Valid @RequestBody(required = false) AcceptConsentRequest request) {
        return settings.acceptConsent(CurrentUser.userId(), request);
    }
}