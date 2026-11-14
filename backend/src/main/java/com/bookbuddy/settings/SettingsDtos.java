package com.bookbuddy.settings;

import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.safety.CrisisResponseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class SettingsDtos {

    private SettingsDtos() {}

    public record ConsentView(boolean accepted, String version, Instant acceptedAt, String currentVersion) {}

    public record CrisisContactDto(String name, String phone) {}

    /** {@code crisisResources} are the deployment-wide helplines (read-only); {@code crisisContacts} are the user's own. */
    public record SettingsResponse(
            ConsentView consent,
            boolean memoryEnabled,
            String defaultLanguage,
            List<CrisisContactDto> crisisContacts,
            List<CrisisResponseService.Resource> crisisResources) {

        public static SettingsResponse from(UserDocument u, List<CrisisResponseService.Resource> resources) {
            UserDocument.Consent c = u.getConsent();
            boolean accepted = ConsentPolicy.hasAccepted(u);
            List<CrisisContactDto> contacts = u.getCrisisContacts() == null
                    ? List.of()
                    : u.getCrisisContacts().stream()
                            .map(x -> new CrisisContactDto(x.name(), x.phone()))
                            .toList();
            return new SettingsResponse(
                    new ConsentView(
                            accepted,
                            c == null ? null : c.version(),
                            c == null ? null : c.acceptedAt(),
                            ConsentPolicy.CURRENT_VERSION),
                    u.isMemoryEnabled(),
                    u.getDefaultLanguage(),
                    contacts,
                    resources);
        }
    }

    public record CrisisContactInput(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[0-9+()\\-\\s]{3,32}$", message = "must be a phone number")
                    String phone) {}

    public record ConsentUpdate(boolean accepted, @Size(max = 32) String version) {}

    /** Every field is optional; only the ones present are changed. */
    public record UpdateSettingsRequest(
            @Valid ConsentUpdate consent,
            Boolean memoryEnabled,
            @Size(max = 16) String defaultLanguage,
            @Size(max = 5) List<@Valid CrisisContactInput> crisisContacts) {}

    public record AcceptConsentRequest(@Size(max = 32) String version) {}
}