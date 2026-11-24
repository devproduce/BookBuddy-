package com.bookbuddy.memory;

import java.util.List;

/** Parsed output of the personal memory engine (§15 JSON contract). */
public record MemoryEngineResult(
        Operation operation,
        ProfileDraft userProfile,
        List<MemoryCandidate> newMemories,
        List<MemoryUpdate> memoryUpdates,
        List<MemoryConsolidation> memoryConsolidations,
        List<OutdatedMemory> outdatedMemories,
        List<String> uncertainties,
        InteractionDraft interactionContext,
        ProcessingMetadata processingMetadata,
        List<String> relevantMemoryIds,
        String suggestedInteractionApproach) {

    public enum Operation {
        HISTORICAL_ANALYSIS,
        INCREMENTAL_ANALYSIS,
        CONSOLIDATION,
        RETRIEVAL
    }

    public MemoryEngineResult {
        newMemories = newMemories == null ? List.of() : List.copyOf(newMemories);
        memoryUpdates = memoryUpdates == null ? List.of() : List.copyOf(memoryUpdates);
        memoryConsolidations = memoryConsolidations == null ? List.of() : List.copyOf(memoryConsolidations);
        outdatedMemories = outdatedMemories == null ? List.of() : List.copyOf(outdatedMemories);
        uncertainties = uncertainties == null ? List.of() : List.copyOf(uncertainties);
        relevantMemoryIds = relevantMemoryIds == null ? List.of() : List.copyOf(relevantMemoryIds);
        suggestedInteractionApproach =
                suggestedInteractionApproach == null ? "" : suggestedInteractionApproach;
        if (userProfile == null) {
            userProfile = ProfileDraft.empty();
        }
        if (interactionContext == null) {
            interactionContext = InteractionDraft.empty();
        }
        if (processingMetadata == null) {
            processingMetadata = ProcessingMetadata.empty();
        }
    }

    public record ProfileDraft(
            List<String> background,
            List<String> interests,
            List<String> goals,
            List<String> values,
            List<String> communicationPreferences,
            List<String> learningPreferences,
            List<String> personalityObservations,
            List<String> thinkingPatterns,
            List<String> importantExperiences,
            List<String> recurringChallenges,
            List<String> currentActivities,
            String profileSummary) {

        public ProfileDraft {
            background = copy(background);
            interests = copy(interests);
            goals = copy(goals);
            values = copy(values);
            communicationPreferences = copy(communicationPreferences);
            learningPreferences = copy(learningPreferences);
            personalityObservations = copy(personalityObservations);
            thinkingPatterns = copy(thinkingPatterns);
            importantExperiences = copy(importantExperiences);
            recurringChallenges = copy(recurringChallenges);
            currentActivities = copy(currentActivities);
            profileSummary = profileSummary == null ? "" : profileSummary;
        }

        public static ProfileDraft empty() {
            return new ProfileDraft(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), "");
        }

        public boolean isBlank() {
            return profileSummary.isBlank()
                    && background.isEmpty()
                    && interests.isEmpty()
                    && goals.isEmpty()
                    && values.isEmpty()
                    && communicationPreferences.isEmpty()
                    && learningPreferences.isEmpty()
                    && personalityObservations.isEmpty()
                    && thinkingPatterns.isEmpty()
                    && importantExperiences.isEmpty()
                    && recurringChallenges.isEmpty()
                    && currentActivities.isEmpty();
        }

        private static List<String> copy(List<String> list) {
            return list == null ? List.of() : List.copyOf(list);
        }
    }

    public record InteractionDraft(
            String preferredTone,
            String preferredDetailLevel,
            List<String> communicationStyle,
            List<String> emotionalSupportPreferences,
            List<String> advicePreferences,
            List<String> personalizationGuidelines,
            String followUpQuestionPreference) {

        public InteractionDraft {
            preferredTone = preferredTone == null ? "" : preferredTone;
            preferredDetailLevel = preferredDetailLevel == null ? "" : preferredDetailLevel;
            followUpQuestionPreference = followUpQuestionPreference == null ? "" : followUpQuestionPreference;
            communicationStyle = communicationStyle == null ? List.of() : List.copyOf(communicationStyle);
            emotionalSupportPreferences =
                    emotionalSupportPreferences == null ? List.of() : List.copyOf(emotionalSupportPreferences);
            advicePreferences = advicePreferences == null ? List.of() : List.copyOf(advicePreferences);
            personalizationGuidelines =
                    personalizationGuidelines == null ? List.of() : List.copyOf(personalizationGuidelines);
        }

        public static InteractionDraft empty() {
            return new InteractionDraft("", "", List.of(), List.of(), List.of(), List.of(), "");
        }

        public boolean isBlank() {
            return preferredTone.isBlank()
                    && preferredDetailLevel.isBlank()
                    && followUpQuestionPreference.isBlank()
                    && communicationStyle.isEmpty()
                    && emotionalSupportPreferences.isEmpty()
                    && advicePreferences.isEmpty()
                    && personalizationGuidelines.isEmpty();
        }
    }

    public record MemoryUpdate(String existingMemoryId, String updatedContent, String reason, double confidence) {}

    public record MemoryConsolidation(List<String> sourceMemoryIds, String consolidatedContent, String reason) {
        public MemoryConsolidation {
            sourceMemoryIds = sourceMemoryIds == null ? List.of() : List.copyOf(sourceMemoryIds);
            consolidatedContent = consolidatedContent == null ? "" : consolidatedContent;
            reason = reason == null ? "" : reason;
        }
    }

    public record OutdatedMemory(String memoryId, String reason) {}

    public record ProcessingMetadata(
            int conversationsAnalyzed,
            int newMemoriesCount,
            int updatedMemoriesCount,
            int consolidatedMemoriesCount,
            List<String> requiresUserConfirmation) {

        public ProcessingMetadata {
            requiresUserConfirmation =
                    requiresUserConfirmation == null ? List.of() : List.copyOf(requiresUserConfirmation);
        }

        public static ProcessingMetadata empty() {
            return new ProcessingMetadata(0, 0, 0, 0, List.of());
        }
    }
}
