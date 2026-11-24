package com.bookbuddy.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persisted personal profile + interaction context for one user. Refreshed by historical / incremental /
 * consolidation analysis; loaded at chat time so the reply LLM does not rebuild from every memory.
 */
@Document("user_profiles")
@Getter
@Setter
@NoArgsConstructor
public class UserProfileDocument {

    @Id
    private String id;

    private String userId;

    private List<String> background = new ArrayList<>();
    private List<String> interests = new ArrayList<>();
    private List<String> goals = new ArrayList<>();
    private List<String> values = new ArrayList<>();
    private List<String> communicationPreferences = new ArrayList<>();
    private List<String> learningPreferences = new ArrayList<>();
    private List<String> personalityObservations = new ArrayList<>();
    private List<String> thinkingPatterns = new ArrayList<>();
    private List<String> importantExperiences = new ArrayList<>();
    private List<String> recurringChallenges = new ArrayList<>();
    private List<String> currentActivities = new ArrayList<>();

    private String profileSummary = "";

    private InteractionContext interactionContext = new InteractionContext();

    private Instant updatedAt;
    private Instant createdAt;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class InteractionContext {
        private String preferredTone = "";
        private String preferredDetailLevel = "";
        private List<String> communicationStyle = new ArrayList<>();
        private List<String> emotionalSupportPreferences = new ArrayList<>();
        private List<String> advicePreferences = new ArrayList<>();
        private List<String> personalizationGuidelines = new ArrayList<>();
        private String followUpQuestionPreference = "";
    }
}
