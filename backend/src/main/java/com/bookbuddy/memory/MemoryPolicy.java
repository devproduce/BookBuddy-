package com.bookbuddy.memory;

import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Classification;
import com.bookbuddy.memory.MemoryDocument.Status;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/** The non-negotiable rules about what may be remembered, for how long, and how sure we may sound. */
public final class MemoryPolicy {

    private MemoryPolicy() {}

    /** An INFERRED memory is never more certain than this until the user confirms it. */
    public static final double INFERRED_CONFIDENCE_CAP = 0.7;

    /** Candidates the model itself is unsure about are not worth keeping. */
    public static final double MIN_CANDIDATE_CONFIDENCE = 0.4;

    public static final int MAX_CONTENT_CHARS = 300;
    public static final int MIN_CONTENT_CHARS = 3;

    // ---------------------------------------------------------------------------------------------------------
    // Screening of LLM candidates
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Applies the safety rules to a validated candidate.
     *
     * <ul>
     *   <li>An EXPLICIT claim must be backed by {@code evidence} that really occurs in the user's own messages;
     *       otherwise it is downgraded to INFERRED.
     *   <li>Sensitive content (model flag or {@link SensitiveTopics}) is dropped unless EXPLICIT, and is then
     *       flagged sensitive. Sensitive categories are never inferred.
     *   <li>INFERRED confidence is capped at {@link #INFERRED_CONFIDENCE_CAP}.
     * </ul>
     *
     * @param userText everything the user wrote in the extraction window
     * @return the possibly adjusted candidate, or empty when it must be discarded
     */
    public static Optional<MemoryCandidate> screen(MemoryCandidate c, String userText) {
        String content = sanitize(c.content());
        if (content.length() < MIN_CONTENT_CHARS) {
            return Optional.empty();
        }
        Classification classification = c.classification();
        if (classification == Classification.EXPLICIT && !evidenceFound(c.evidence(), userText)) {
            classification = Classification.INFERRED;
        }
        boolean sensitive = c.sensitive() || SensitiveTopics.isSensitive(content) || SensitiveTopics.isSensitive(c.evidence());
        if (sensitive && classification != Classification.EXPLICIT) {
            return Optional.empty();
        }
        double confidence = clamp01(c.confidence());
        if (classification == Classification.INFERRED) {
            confidence = Math.min(confidence, INFERRED_CONFIDENCE_CAP);
        }
        if (confidence < MIN_CANDIDATE_CONFIDENCE) {
            return Optional.empty();
        }
        return Optional.of(new MemoryCandidate(
                c.category(), content, classification, confidence, clamp01(c.importance()), sensitive,
                c.replacesPrevious(), c.evidence(), c.sourceMessageIds()));
    }

    /** True when {@code evidence} (a quote of the user's words) appears in {@code userText}, ignoring case and spacing. */
    public static boolean evidenceFound(String evidence, String userText) {
        String e = normalize(evidence);
        if (e.length() < 4 || userText == null) {
            return false;
        }
        return normalize(userText).contains(e);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Expiry
    // ---------------------------------------------------------------------------------------------------------

    /** EMOTIONAL_CONTEXT fades after {@code days} unless pinned; everything else never expires on its own. */
    public static Instant defaultExpiry(Category category, boolean pinned, Instant from, int days) {
        if (category != Category.EMOTIONAL_CONTEXT || pinned) {
            return null;
        }
        return from.plus(Duration.ofDays(days));
    }

    public static boolean isExpired(MemoryDocument m, Instant now) {
        if (m.getStatus() == Status.EXPIRED) {
            return true;
        }
        return !m.isPinned() && m.getExpiresAt() != null && !m.getExpiresAt().isAfter(now);
    }

    /** May this memory be shown to the model right now? Archived, expired, superseded and deleted ones may not. */
    public static boolean isUsable(MemoryDocument m, Instant now) {
        return m.getStatus() == Status.ACTIVE
                && m.getSupersededBy() == null
                && !isExpired(m, now)
                && m.getContent() != null
                && !m.getContent().isBlank();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Text hygiene
    // ---------------------------------------------------------------------------------------------------------

    /** One line, no control characters, no forged prompt delimiters, bounded length. */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replace("<<<", "< < <")
                .replace(">>>", "> > >")
                .replaceAll("\\s+", " ")
                .strip();
        return s.length() <= MAX_CONTENT_CHARS ? s : s.substring(0, MAX_CONTENT_CHARS).stripTrailing();
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        n = n.replace('\u2019', '\'').replace('\u2018', '\'').replace('\u201C', '"').replace('\u201D', '"');
        return n.replaceAll("[\\p{Punct}\\s]+", " ").strip();
    }

    public static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return Math.max(0, Math.min(1, v));
    }
}
