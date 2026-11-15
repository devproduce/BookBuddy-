package com.bookbuddy.conversation;

import com.bookbuddy.ai.ChatMessage;
import com.bookbuddy.ai.LlmException;
import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.document.TextTokens;
import com.bookbuddy.quota.LlmBudgetService;
import com.bookbuddy.quota.LlmBudgetService.Priority;
import com.bookbuddy.rag.RagProperties;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Turns a follow-up ("why did he do that?", "isme aur kya hua?") into a standalone retrieval query using the earlier
 * turns, so book search is not run on a message that only makes sense in context. Only the search query changes: the
 * model still sees the user's original words.
 *
 * <p>Default mode is a free heuristic; {@code bookbuddy.rag.rewrite-mode=llm} adds one small LLM call (only for
 * messages that look like follow-ups, only while the budget allows) and falls back to the heuristic on any failure.
 */
@Service
public class RewriteService {

    private static final Logger log = LoggerFactory.getLogger(RewriteService.class);

    public static final String METHOD_NONE = "none";
    public static final String METHOD_HEURISTIC = "heuristic";
    public static final String METHOD_LLM = "llm";

    /** The query to search with. {@code rewritten} is false when it is just the original message. */
    public record Result(String query, boolean rewritten, String method) {}

    static final int MAX_QUERY_CHARS = 300;
    private static final int MAX_ANCHOR_CHARS = 200;
    private static final int MAX_FOLLOW_UP_WORDS = 12;
    private static final int LLM_CONTEXT_MESSAGES = 4;
    private static final int LLM_CONTEXT_CHARS = 300;

    /** Opening words that continue the previous topic whatever else the message says. */
    private static final Set<String> LEAD_ALWAYS = Set.of(
            "and", "but", "also", "more", "continue", "elaborate", "explain", "aur", "phir", "lekin", "magar",
            "\u0914\u0930", "\u092B\u093F\u0930", "\u0932\u0947\u0915\u093F\u0928");
    private static final List<String> LEAD_PHRASES = List.of(
            "what about", "how about", "tell me more", "go on", "what else", "what happened next", "aur batao",
            "aur kuch", "\u0914\u0930 \u092C\u0924\u093E\u0913");
    /** Question words that only count as a follow-up when little else is said ("why?", "how so?"). */
    private static final Set<String> LEAD_SHORT = Set.of(
            "why", "how", "so", "then", "kyun", "kyu", "kyon", "kaise", "kaisa", "\u0915\u094D\u092F\u094B\u0902",
            "\u0915\u0948\u0938\u0947");
    /** Words that point back at something said earlier. */
    private static final Set<String> REFERENCES = Set.of(
            "it", "its", "this", "that", "they", "them", "their", "he", "him", "his", "she", "her", "hers", "those",
            "these", "there", "next", "isme", "usme", "iska", "uska", "iski", "uski", "inka", "unka", "iske", "uske",
            "woh", "wo", "voh", "yeh", "ye", "unhone", "usne", "isne", "\u0907\u0938\u092E\u0947\u0902",
            "\u0909\u0938\u092E\u0947\u0902", "\u0907\u0938\u0915\u093E", "\u0909\u0938\u0915\u093E",
            "\u0907\u0938\u0915\u0940", "\u0909\u0938\u0915\u0940", "\u0935\u0939", "\u0935\u094B", "\u092F\u0939",
            "\u092F\u0947", "\u0909\u0928\u094D\u0939\u094B\u0902\u0928\u0947", "\u0909\u0938\u0928\u0947");

    private static final Pattern QUOTES = Pattern.compile("^[\\s\"'`\u201C\u201D\u2018\u2019]+|[\\s\"'`\u201C\u201D\u2018\u2019]+$");
    private static final Pattern LABEL = Pattern.compile("^(?i)(?:standalone )?(?:search )?query\\s*:\\s*");

    private final PromptLoader prompts;
    private final LlmProvider llm;
    private final RagProperties props;
    private volatile LlmBudgetService budget;

    public RewriteService(PromptLoader prompts, LlmProvider llm, RagProperties props) {
        this.prompts = prompts;
        this.llm = llm;
        this.props = props == null ? RagProperties.defaults() : props;
    }

    /** Optional: when present, LLM rewrites yield to chat and safety if the daily budget runs low. */
    @Autowired(required = false)
    public void setBudget(LlmBudgetService budget) {
        this.budget = budget;
    }

    /**
     * Cheap pre-check: does this message depend on earlier turns? Callers fetch history only when it does.
     * Always false when rewriting is switched off.
     */
    public boolean looksLikeFollowUp(String question) {
        if (RagProperties.MODE_OFF.equals(props.rewriteMode()) || question == null) {
            return false;
        }
        List<String> tokens = TextTokens.tokenize(question);
        int n = tokens.size();
        if (n == 0 || n > MAX_FOLLOW_UP_WORDS) {
            return false;
        }
        int content = TextTokens.contentTokens(question).size();
        if (content <= 1 && n <= 6) {
            return true; // "why?", "and then?", "really?"
        }
        String first = tokens.get(0);
        String normalized = String.join(" ", tokens);
        if (n <= 8) {
            if (LEAD_ALWAYS.contains(first)) {
                return true;
            }
            for (String phrase : LEAD_PHRASES) {
                if (normalized.startsWith(phrase)) {
                    return true;
                }
            }
            if (content <= 2 && LEAD_SHORT.contains(first)) {
                return true;
            }
        }
        if (n <= 10 && content <= 5) {
            for (String t : tokens) {
                if (REFERENCES.contains(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param history earlier messages of the conversation, oldest first, not including {@code question}
     * @return the query to retrieve with; never null, never blank
     */
    public Result rewrite(String userId, String question, List<ChatMessage> history) {
        Result unchanged = new Result(question, false, METHOD_NONE);
        if (question == null || question.isBlank() || history == null || history.isEmpty()
                || !looksLikeFollowUp(question)) {
            return unchanged;
        }
        if (RagProperties.MODE_LLM.equals(props.rewriteMode())) {
            String viaLlm = rewriteWithLlm(userId, question, history);
            if (viaLlm != null) {
                return new Result(viaLlm, !viaLlm.equalsIgnoreCase(question.strip()), METHOD_LLM);
            }
        }
        String viaHeuristic = rewriteHeuristically(question, history);
        return viaHeuristic == null ? unchanged : new Result(viaHeuristic, true, METHOD_HEURISTIC);
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Prepends the most recent standalone user question; null when there is nothing to anchor on. */
    String rewriteHeuristically(String question, List<ChatMessage> history) {
        String anchor = null;
        String fallback = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (!"user".equals(m.role()) || m.content() == null) {
                continue;
            }
            String text = m.content().strip();
            if (text.equalsIgnoreCase(question.strip()) || TextTokens.contentTokens(text).size() < 2) {
                continue;
            }
            if (fallback == null) {
                fallback = text;
            }
            if (!looksLikeFollowUp(text)) {
                anchor = text;
                break;
            }
            if (history.size() - i > 6) {
                break; // do not walk back through a long run of follow-ups
            }
        }
        if (anchor == null) {
            anchor = fallback;
        }
        if (anchor == null) {
            return null;
        }
        String shortAnchor = anchor.replaceAll("\\s+", " ");
        if (shortAnchor.length() > MAX_ANCHOR_CHARS) {
            shortAnchor = shortAnchor.substring(0, MAX_ANCHOR_CHARS).stripTrailing();
        }
        String combined = shortAnchor + " " + question.strip().replaceAll("\\s+", " ");
        return combined.length() <= MAX_QUERY_CHARS ? combined : combined.substring(0, MAX_QUERY_CHARS).stripTrailing();
    }

    private String rewriteWithLlm(String userId, String question, List<ChatMessage> history) {
        if (llm == null || !llm.isConfigured() || (budget != null && !budget.allows(Priority.SUMMARY))) {
            return null;
        }
        StringBuilder user = new StringBuilder("Earlier messages:\n");
        int from = Math.max(0, history.size() - LLM_CONTEXT_MESSAGES);
        for (int i = from; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            if (m.content() == null || m.content().isBlank()) {
                continue;
            }
            String text = m.content().strip().replaceAll("\\s+", " ");
            if (text.length() > LLM_CONTEXT_CHARS) {
                text = text.substring(0, LLM_CONTEXT_CHARS) + "...";
            }
            user.append("assistant".equals(m.role()) ? "Assistant: " : "User: ").append(text).append('\n');
        }
        user.append("\nLatest message: ").append(question.strip()).append("\n\nStandalone query:");

        long started = System.currentTimeMillis();
        try {
            String raw = llm.complete(prompts.queryRewritePrompt(), user.toString());
            if (budget != null) {
                budget.record(userId, Priority.CHAT, null, null, System.currentTimeMillis() - started, true);
            }
            return sanitize(raw);
        } catch (LlmException | IllegalStateException e) {
            log.debug("Query rewrite via LLM failed ({}); using heuristic", e.getClass().getSimpleName());
            if (budget != null && e instanceof LlmException) {
                budget.record(userId, Priority.CHAT, null, null, System.currentTimeMillis() - started, false);
            }
            return null;
        }
    }

    /** One clean line, or null if the model produced something unusable. */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String line = null;
        for (String l : raw.strip().split("\\R")) {
            if (!l.isBlank()) {
                line = l;
                break;
            }
        }
        if (line == null) {
            return null;
        }
        line = LABEL.matcher(QUOTES.matcher(line.strip()).replaceAll("")).replaceFirst("");
        line = QUOTES.matcher(line).replaceAll("").replaceAll("\\s+", " ");
        if (line.isBlank() || line.contains("<<<") || line.contains(">>>")
                || TextTokens.tokenize(line).size() > 60) {
            return null;
        }
        return line.length() <= MAX_QUERY_CHARS ? line : line.substring(0, MAX_QUERY_CHARS).stripTrailing();
    }
}
