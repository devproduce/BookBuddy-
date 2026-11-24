package com.bookbuddy.memory;

import com.bookbuddy.ai.LlmException;
import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.ai.TokenUsage;
import com.bookbuddy.conversation.MessageDocument;
import com.bookbuddy.conversation.PromptLoader;
import com.bookbuddy.conversation.TokenEstimator;
import com.bookbuddy.jobs.JobHandler.BudgetDeferredException;
import com.bookbuddy.memory.MemoryEngineResult.Operation;
import com.bookbuddy.quota.LlmBudgetService;
import com.bookbuddy.quota.LlmBudgetService.Priority;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calls Groq with the memory.engine prompt for HISTORICAL / INCREMENTAL / CONSOLIDATION / RETRIEVAL modes.
 * Returns parsed {@link MemoryEngineResult}; never writes to the database.
 */
@Component
public class MemoryEngineClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryEngineClient.class);

    public static final String PROMPT_ID = "memory.engine.v1";
    static final int MAX_TOKENS = 3500;
    static final String RETRY_NOTE =
            "\n\nYour previous reply did not match the required JSON schema. Reply with ONLY the JSON object.";

    public static class InvalidOutputException extends RuntimeException {
        public InvalidOutputException(String message) {
            super(message, null, false, false);
        }
    }

    private final LlmProvider llm;
    private final LlmBudgetService budget;
    private final PromptLoader prompts;

    public MemoryEngineClient(LlmProvider llm, LlmBudgetService budget, PromptLoader prompts) {
        this.llm = llm;
        this.budget = budget;
        this.prompts = prompts;
    }

    public MemoryEngineResult analyzeIncremental(
            String userId,
            String conversationId,
            List<MessageDocument> window,
            List<MemoryDocument> existingMemories) {
        Set<String> messageIds = new HashSet<>();
        StringBuilder userText = new StringBuilder();
        String transcript = MemoryExtractor.transcript(window, messageIds, userText);
        if (userText.length() == 0) {
            return empty(Operation.INCREMENTAL_ANALYSIS);
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("operation: INCREMENTAL_ANALYSIS\n");
        prompt.append("conversation_id: ").append(conversationId == null ? "" : conversationId).append('\n');
        prompt.append("Analyze this new conversation window and propose memory changes.\n\n");
        prompt.append("EXISTING MEMORIES:\n").append(formatMemories(existingMemories)).append("\n\n");
        prompt.append("CONVERSATION:\n").append(transcript);
        return run(userId, Priority.EXTRACTION, Operation.INCREMENTAL_ANALYSIS, prompt.toString(), messageIds,
                conversationId == null ? Set.of() : Set.of(conversationId), userText.toString());
    }

    public MemoryEngineResult analyzeHistorical(
            String userId,
            List<MessageDocument> window,
            Set<String> conversationIds,
            List<MemoryDocument> draftMemories,
            boolean finalizeProfile) {
        Set<String> messageIds = new HashSet<>();
        StringBuilder userText = new StringBuilder();
        String transcript = MemoryExtractor.transcript(window, messageIds, userText);
        if (userText.length() == 0) {
            return empty(Operation.HISTORICAL_ANALYSIS);
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("operation: HISTORICAL_ANALYSIS\n");
        prompt.append("finalize_profile: ").append(finalizeProfile).append('\n');
        prompt.append("Analyze this historical conversation batch.\n\n");
        prompt.append("DRAFT MEMORIES SO FAR:\n").append(formatMemories(draftMemories)).append("\n\n");
        prompt.append("CONVERSATION:\n").append(transcript);
        return run(userId, Priority.EXTRACTION, Operation.HISTORICAL_ANALYSIS, prompt.toString(), messageIds,
                conversationIds == null ? Set.of() : conversationIds, userText.toString());
    }

    public MemoryEngineResult consolidate(String userId, List<MemoryDocument> activeMemories) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("operation: CONSOLIDATION\n");
        prompt.append("Review these active memories. Resolve duplicates, mark outdated items, refresh the profile.\n\n");
        prompt.append("ACTIVE MEMORIES:\n").append(formatMemories(activeMemories));
        return run(userId, Priority.CONSOLIDATION, Operation.CONSOLIDATION, prompt.toString(), Set.of(), Set.of(), "");
    }

    public MemoryEngineResult retrieve(
            String userId, String message, List<MemoryDocument> candidates, UserProfileDocument profile) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("operation: RETRIEVAL\n");
        prompt.append("Select relevant memory ids and interaction guidance for this user message.\n\n");
        prompt.append("USER MESSAGE:\n").append(message == null ? "" : message).append("\n\n");
        if (profile != null && profile.getProfileSummary() != null && !profile.getProfileSummary().isBlank()) {
            prompt.append("PROFILE SUMMARY:\n").append(profile.getProfileSummary()).append("\n\n");
        }
        prompt.append("CANDIDATE MEMORIES:\n").append(formatMemories(candidates));
        return run(userId, Priority.CHAT, Operation.RETRIEVAL, prompt.toString(), Set.of(), Set.of(), message);
    }

    private MemoryEngineResult run(
            String userId,
            Priority priority,
            Operation expected,
            String userPrompt,
            Set<String> messageIds,
            Set<String> conversationIds,
            String userTextForScreen) {
        String system = systemPrompt();
        MemoryEngineResultParser.Result parsed = null;
        for (int attempt = 0; attempt < 2 && (parsed == null || !parsed.valid()); attempt++) {
            if (!budget.allows(priority)) {
                throw new BudgetDeferredException("LLM budget low");
            }
            String raw = call(userId, priority, system, attempt == 0 ? userPrompt : userPrompt + RETRY_NOTE);
            parsed = MemoryEngineResultParser.parse(raw, messageIds, conversationIds);
            if (!parsed.valid()) {
                log.debug("Memory engine output invalid (attempt {}, op={})", attempt + 1, expected);
            }
        }
        if (parsed == null || !parsed.valid()) {
            throw new InvalidOutputException("The model did not return valid memory engine JSON");
        }
        MemoryEngineResult result = parsed.value();
        if (userTextForScreen == null || userTextForScreen.isBlank()) {
            return result;
        }
        List<MemoryCandidate> screened = new ArrayList<>();
        for (MemoryCandidate c : result.newMemories()) {
            MemoryPolicy.screen(c, userTextForScreen).ifPresent(screened::add);
        }
        return new MemoryEngineResult(
                result.operation() == null ? expected : result.operation(),
                result.userProfile(),
                screened,
                result.memoryUpdates(),
                result.memoryConsolidations(),
                result.outdatedMemories(),
                result.uncertainties(),
                result.interactionContext(),
                result.processingMetadata(),
                result.relevantMemoryIds(),
                result.suggestedInteractionApproach());
    }

    private String call(String userId, Priority priority, String system, String prompt) {
        long started = System.currentTimeMillis();
        try {
            String out = llm.complete(system, prompt, MAX_TOKENS);
            budget.record(
                    userId,
                    priority,
                    null,
                    TokenUsage.of(
                            TokenEstimator.estimate(system) + TokenEstimator.estimate(prompt),
                            TokenEstimator.estimate(out),
                            true),
                    System.currentTimeMillis() - started,
                    true);
            return out;
        } catch (LlmException e) {
            budget.record(userId, priority, null, null, System.currentTimeMillis() - started, false);
            throw e;
        }
    }

    private String systemPrompt() {
        if (prompts.exists(PROMPT_ID)) {
            return prompts.load(PROMPT_ID);
        }
        return MemoryExtractor.SYSTEM;
    }

    static String formatMemories(List<MemoryDocument> memories) {
        if (memories == null || memories.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (MemoryDocument m : memories) {
            if (i++ >= 80) {
                sb.append("... (").append(memories.size() - 80).append(" more omitted)\n");
                break;
            }
            sb.append("- id=")
                    .append(m.getId())
                    .append(" category=")
                    .append(m.getCategory())
                    .append(" source=")
                    .append(m.getSourceType())
                    .append(" importance=")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", m.getImportance()))
                    .append(" :: ")
                    .append(m.getContent())
                    .append('\n');
        }
        return sb.toString();
    }

    private static MemoryEngineResult empty(Operation op) {
        return new MemoryEngineResult(
                op,
                MemoryEngineResult.ProfileDraft.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                MemoryEngineResult.InteractionDraft.empty(),
                MemoryEngineResult.ProcessingMetadata.empty(),
                List.of(),
                "");
    }
}
