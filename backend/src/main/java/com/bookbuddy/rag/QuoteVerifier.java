package com.bookbuddy.rag;

import com.bookbuddy.document.TextCleaningService;
import com.bookbuddy.document.TextTokens;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Guards against hallucinated quotations: every double-quoted span in an answer must be a verbatim substring (after
 * whitespace / quote-style normalisation) of a retrieved passage. A span that is not is replaced by the closest
 * source sentence when one is very similar, otherwise the quotation marks are stripped so it no longer claims to be a
 * quote. Ellipses inside a quote ("first part ... second part") are checked fragment by fragment.
 */
@Component
public class QuoteVerifier {

    public record Result(String text, int verified, int fixed, int stripped) {}

    private static final Pattern QUOTE =
            Pattern.compile("\"([^\"\\n]{1,1500}?)\"|\u201C([^\u201D\\n]{1,1500}?)\u201D");
    private static final Pattern ELLIPSIS = Pattern.compile("\\s*(?:\\.{3,}|\u2026)\\s*");
    private static final Pattern EDGE_PUNCT = Pattern.compile("^[\\s.,;:!?\\-\u2013\u2014]+|[\\s.,;:!?\\-\u2013\u2014]+$");
    private static final int MIN_CHECKED_LENGTH = 4;
    private static final double FIX_SIMILARITY = 0.6;

    private final TextCleaningService cleaning;

    public QuoteVerifier(TextCleaningService cleaning) {
        this.cleaning = cleaning;
    }

    public Result verify(String answer, Collection<String> sourceTexts) {
        if (answer == null || answer.isEmpty()) {
            return new Result(answer == null ? "" : answer, 0, 0, 0);
        }
        List<String> sources = new ArrayList<>();
        for (String s : sourceTexts) {
            sources.add(normalize(s));
        }

        Matcher m = QUOTE.matcher(answer);
        StringBuilder out = new StringBuilder();
        int verified = 0;
        int fixed = 0;
        int stripped = 0;
        while (m.find()) {
            String body = m.group(1) != null ? m.group(1) : m.group(2);
            String whole = m.group();
            String replacement;
            if (isVerbatim(body, sources)) {
                verified++;
                replacement = whole;
            } else {
                String sentence = closestSentence(body, sourceTexts);
                if (sentence != null) {
                    fixed++;
                    replacement = whole.charAt(0) + sentence + whole.charAt(whole.length() - 1);
                } else {
                    stripped++;
                    replacement = body;
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return new Result(out.toString(), verified, fixed, stripped);
    }

    public boolean isVerbatim(String quote, Collection<String> normalizedSources) {
        String normalized = normalize(quote);
        if (EDGE_PUNCT.matcher(normalized).replaceAll("").length() < MIN_CHECKED_LENGTH) {
            return true; // too short to be a meaningful quotation (scare quotes, single words)
        }
        for (String fragment : ELLIPSIS.split(normalized)) {
            String f = EDGE_PUNCT.matcher(fragment).replaceAll("");
            if (f.isEmpty()) {
                continue;
            }
            boolean found = false;
            for (String source : normalizedSources) {
                if (source.contains(f)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private String closestSentence(String quote, Collection<String> sourceTexts) {
        Set<String> q = new HashSet<>(TextTokens.contentTokens(quote));
        if (q.size() < 3) {
            return null;
        }
        String best = null;
        double bestScore = 0;
        for (String source : sourceTexts) {
            for (String sentence : cleaning.splitSentences(source)) {
                Set<String> s = new HashSet<>(TextTokens.contentTokens(sentence));
                if (s.isEmpty()) {
                    continue;
                }
                Set<String> inter = new HashSet<>(q);
                inter.retainAll(s);
                double union = q.size() + s.size() - inter.size();
                double jaccard = inter.size() / union;
                if (jaccard > bestScore) {
                    bestScore = jaccard;
                    best = sentence;
                }
            }
        }
        if (best == null || bestScore < FIX_SIMILARITY || best.indexOf('"') >= 0 || best.indexOf('\u201C') >= 0
                || best.indexOf('\u201D') >= 0) {
            return null;
        }
        return best;
    }

    /** NFC, unify quote/dash styles, collapse whitespace. Case is preserved: quotes must match exactly. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFC)
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u2013', '-')
                .replace('\u2014', '-');
        return n.replaceAll("\\s+", " ").strip();
    }
}
