package com.bookbuddy.document;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Cleans text extracted from PDFs: strips running headers/footers and page numbers, de-hyphenates, merges
 * hard-wrapped lines, NFC-normalises, detects Devanagari and splits sentences (on {@code । . ? !}).
 */
@Service
public class TextCleaningService {

    public record LanguageGuess(String code, double confidence) {}

    private static final Pattern PAGE_NUMBER_LINE = Pattern.compile(
            "^(?:page\\s*|pg\\.?\\s*|पृष्ठ\\s*)?[-–—\\[(]?\\s*"
                    + "(?:[0-9\u0966-\u096F]{1,5}"
                    + "|i|ii|iii|iv|v|vi|vii|viii|ix|x|xi|xii|xiii|xiv|xv|xvi|xvii|xviii|xix|xx)"
                    + "\\s*[-–—\\])]?(?:\\s*(?:/|of)\\s*[0-9\u0966-\u096F]{1,5})?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DIGITS = Pattern.compile("[0-9\u0966-\u096F]+");
    private static final Pattern INLINE_SPACE = Pattern.compile("[ \\t\\u00A0\\u2000-\\u200A\\u202F\\u3000]+");
    private static final Pattern INVISIBLE = Pattern.compile("[\\u00AD\\u200B\\uFEFF\\u2060\\u0000]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");

    private static final Set<String> ABBREVIATIONS = Set.of(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "fig", "no", "vol", "ch",
            "pp", "inc", "ltd", "co", "approx", "ed", "eds", "cf", "viz", "mt");

    /** Lines checked at the top and bottom of each page for running headers/footers. */
    private static final int EDGE_LINES = 2;

    /** Minimum number of pages before repeated header/footer detection kicks in. */
    private static final int MIN_PAGES_FOR_REPEAT_DETECTION = 4;

    // ---------------------------------------------------------------------------------------------------------
    // Page level cleaning
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Cleans a whole document. Repeated headers/footers (same text modulo digits on at least ~40% of pages) and
     * stand-alone page numbers at the page edges are removed.
     */
    public List<PageText> cleanPages(List<String> rawPages) {
        int total = rawPages.size();
        List<List<String>> pages = new ArrayList<>(total);
        for (String raw : rawPages) {
            pages.add(splitLines(sanitize(raw)));
        }

        Set<String> repeated = detectRepeatedEdgeLines(pages);

        List<PageText> out = new ArrayList<>(total);
        for (int p = 0; p < total; p++) {
            List<String> kept = stripEdgeNoise(pages.get(p), repeated);
            String rawText = String.join("\n", kept);
            out.add(new PageText(p + 1, mergeLines(kept), rawText));
        }
        return out;
    }

    /** Cleans a single block of text (no cross-page header/footer detection). */
    public String clean(String raw) {
        List<String> lines = splitLines(sanitize(raw));
        List<String> kept = stripEdgeNoise(lines, Set.of());
        return mergeLines(kept);
    }

    private String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\f', '\n');
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        s = INVISIBLE.matcher(s).replaceAll("");
        s = CONTROL.matcher(s).replaceAll("");
        return s;
    }

    private static List<String> splitLines(String s) {
        List<String> lines = new ArrayList<>();
        for (String line : s.split("\n", -1)) {
            lines.add(INLINE_SPACE.matcher(line).replaceAll(" ").strip());
        }
        return lines;
    }

    private Set<String> detectRepeatedEdgeLines(List<List<String>> pages) {
        int total = pages.size();
        if (total < MIN_PAGES_FOR_REPEAT_DETECTION) {
            return Set.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (List<String> lines : pages) {
            Set<String> seen = new HashSet<>();
            for (int idx : edgeIndexes(lines)) {
                String key = edgeKey(lines.get(idx));
                if (key != null) {
                    seen.add(key);
                }
            }
            for (String key : seen) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        int threshold = Math.max(3, (int) Math.ceil(total * 0.4));
        Set<String> repeated = new HashSet<>();
        counts.forEach((key, count) -> {
            if (count >= threshold) {
                repeated.add(key);
            }
        });
        return repeated;
    }

    /** Indexes of the first and last {@link #EDGE_LINES} non-blank lines. */
    private static List<Integer> edgeIndexes(List<String> lines) {
        List<Integer> nonBlank = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isEmpty()) {
                nonBlank.add(i);
            }
        }
        Set<Integer> edge = new HashSet<>();
        for (int i = 0; i < Math.min(EDGE_LINES, nonBlank.size()); i++) {
            edge.add(nonBlank.get(i));
        }
        for (int i = Math.max(0, nonBlank.size() - EDGE_LINES); i < nonBlank.size(); i++) {
            edge.add(nonBlank.get(i));
        }
        return new ArrayList<>(edge);
    }

    private static String edgeKey(String line) {
        String t = line.strip();
        if (t.length() < 3 || t.length() > 160) {
            return null;
        }
        return DIGITS.matcher(t.toLowerCase(Locale.ROOT)).replaceAll("#").replaceAll("\\s+", " ");
    }

    private static List<String> stripEdgeNoise(List<String> lines, Set<String> repeated) {
        Set<Integer> drop = new HashSet<>();
        for (int idx : edgeIndexes(lines)) {
            String line = lines.get(idx);
            if (PAGE_NUMBER_LINE.matcher(line).matches()) {
                drop.add(idx);
                continue;
            }
            String key = edgeKey(line);
            if (key != null && repeated.contains(key)) {
                drop.add(idx);
            }
        }
        List<String> kept = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (!drop.contains(i)) {
                kept.add(lines.get(i));
            }
        }
        return kept;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Line merging / de-hyphenation
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Merges hard-wrapped lines into paragraphs (blank line = paragraph break) and repairs words hyphenated at line
     * ends. Short lines that end a sentence are kept as line breaks (headings, verses, list items).
     */
    String mergeLines(List<String> lines) {
        List<List<String>> paragraphs = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) {
                if (!current.isEmpty()) {
                    paragraphs.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(line);
            }
        }
        if (!current.isEmpty()) {
            paragraphs.add(current);
        }

        List<String> merged = new ArrayList<>(paragraphs.size());
        for (List<String> para : paragraphs) {
            merged.add(mergeParagraph(para));
        }
        return String.join("\n\n", merged).strip();
    }

    private static String mergeParagraph(List<String> lines) {
        int maxLen = 0;
        for (String l : lines) {
            maxLen = Math.max(maxLen, l.length());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0) {
                sb.append(line);
                continue;
            }
            String prev = lines.get(i - 1);
            char last = prev.charAt(prev.length() - 1);
            char first = line.charAt(0);

            if (last == '-' && prev.length() > 1 && Character.isLetter(prev.charAt(prev.length() - 2))
                    && Character.isLetter(first)) {
                if (Character.isLowerCase(first) || isIndic(first)) {
                    sb.setLength(sb.length() - 1); // de-hyphenate: "informa-\ntion" -> "information"
                }
                sb.append(line); // "Anglo-\nSaxon" keeps its genuine hyphen
            } else if (isSentenceEnd(last) && prev.length() < maxLen * 0.7 && maxLen >= 40) {
                sb.append('\n').append(line);
            } else {
                sb.append(' ').append(line);
            }
        }
        return sb.toString();
    }

    private static boolean isIndic(char c) {
        return c >= '\u0900' && c <= '\u097F';
    }

    private static boolean isSentenceEnd(char c) {
        return c == '.' || c == '?' || c == '!' || c == '\u0964' || c == '\u0965' || c == ':';
    }

    // ---------------------------------------------------------------------------------------------------------
    // Script / language detection
    // ---------------------------------------------------------------------------------------------------------

    public boolean containsDevanagari(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (isIndic(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Share of letters that are Devanagari, 0..1 (0 when there are no letters). */
    public double devanagariRatio(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int letters = 0;
        int deva = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isIndic(c)) {
                if (Character.isLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK
                        || Character.getType(c) == Character.COMBINING_SPACING_MARK) {
                    letters++;
                    deva++;
                }
            } else if (Character.isLetter(c)) {
                letters++;
            }
        }
        return letters == 0 ? 0 : (double) deva / letters;
    }

    /** {@code hi} (mostly Devanagari), {@code en} (Latin / other) or {@code mixed}. */
    public LanguageGuess detectLanguage(String text) {
        double ratio = devanagariRatio(text);
        if (ratio >= 0.7) {
            return new LanguageGuess("hi", ratio);
        }
        if (ratio <= 0.05) {
            return new LanguageGuess("en", 1 - ratio);
        }
        return new LanguageGuess("mixed", 1 - Math.abs(0.5 - ratio));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Sentence splitting
    // ---------------------------------------------------------------------------------------------------------

    /** Splits text into sentences on {@code । ॥ . ? !} (plus paragraph breaks). Whitespace inside is collapsed. */
    public List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String paragraph : text.split("\\n\\s*\\n")) {
            splitParagraph(paragraph.strip(), out);
        }
        return out;
    }

    private static void splitParagraph(String t, List<String> out) {
        int n = t.length();
        int start = 0;
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (!isTerminator(c)) {
                continue;
            }
            int j = i + 1;
            while (j < n && (isTerminator(t.charAt(j)) || isCloser(t.charAt(j)))) {
                j++;
            }
            boolean atEnd = j >= n;
            if (!atEnd && !Character.isWhitespace(t.charAt(j))) {
                i = j - 1; // e.g. "3.14" or "e.g.,x" - not a boundary
                continue;
            }
            if (c == '.' && !atEnd && !isRealPeriodBoundary(t, i, j)) {
                i = j - 1;
                continue;
            }
            addSentence(out, t.substring(start, j));
            start = j;
            i = j - 1;
        }
        if (start < n) {
            addSentence(out, t.substring(start));
        }
    }

    private static void addSentence(List<String> out, String s) {
        String collapsed = s.replaceAll("\\s+", " ").strip();
        if (!collapsed.isEmpty()) {
            out.add(collapsed);
        }
    }

    private static boolean isTerminator(char c) {
        return c == '.' || c == '?' || c == '!' || c == '\u0964' || c == '\u0965' || c == '\u2026';
    }

    private static boolean isCloser(char c) {
        return c == '"' || c == '\'' || c == ')' || c == ']' || c == '\u201D' || c == '\u2019' || c == '\u00BB';
    }

    /** Distinguishes a sentence-ending period from abbreviations ("Dr. Smith", "J. K. Rowling") and mid-sentence ones. */
    private static boolean isRealPeriodBoundary(String t, int dot, int afterRun) {
        int ws = dot;
        while (ws > 0 && !Character.isWhitespace(t.charAt(ws - 1))) {
            ws--;
        }
        String word = t.substring(ws, dot).replaceAll("^[(\"'\\u201C\\u2018\\[]+", "");
        String lower = word.toLowerCase(Locale.ROOT);
        if (ABBREVIATIONS.contains(lower)) {
            return false;
        }
        if (word.length() == 1 && Character.isUpperCase(word.charAt(0))) {
            return false; // initial
        }
        int next = afterRun;
        while (next < t.length() && Character.isWhitespace(t.charAt(next))) {
            next++;
        }
        // a lower-case continuation means the period was not a sentence end ("approx. five")
        return !(next < t.length() && Character.isLowerCase(t.charAt(next)) && dot + 1 == afterRun);
    }
}
