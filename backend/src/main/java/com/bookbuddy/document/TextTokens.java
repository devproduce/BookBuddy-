package com.bookbuddy.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Tiny shared tokenizer used by the hash embedder and keyword search. Handles Latin and Devanagari. */
public final class TextTokens {

    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "is", "are", "was", "were", "be", "been",
            "it", "its", "this", "that", "these", "those", "for", "with", "as", "by", "from", "what", "who", "whom",
            "which", "how", "why", "when", "where", "does", "did", "do", "about", "into", "than", "then", "there",
            "का", "की", "के", "है", "हैं", "और", "में", "से", "को", "पर", "यह", "वह", "था", "थे", "थी", "ने", "क्या",
            "कि", "भी", "तो");

    private TextTokens() {}

    /** Lower-cased word tokens (stop-words included). */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String t : SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    public static boolean isStopword(String token) {
        return STOPWORDS.contains(token);
    }

    /** Tokens with stop-words and one-character tokens removed. */
    public static List<String> contentTokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : tokenize(text)) {
            if (t.codePointCount(0, t.length()) > 1 && !isStopword(t)) {
                out.add(t);
            }
        }
        return out;
    }
}
