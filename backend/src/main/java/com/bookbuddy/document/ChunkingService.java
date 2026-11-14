package com.bookbuddy.document;

import com.bookbuddy.config.BookBuddyProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Sentence-aware chunker: ~{@code chunkTokens} tokens per chunk with ~{@code overlapTokens} of trailing overlap.
 * Tokens are estimated (4 Latin chars or ~2.5 Devanagari chars per token) until a real E5 tokenizer is wired in.
 * Chunks never cut a sentence unless a single sentence is larger than the chunk budget.
 */
@Service
public class ChunkingService {

    /** Bump when the chunking algorithm changes so stored chunks can be identified and rebuilt. */
    public static final String VERSION = "sent-v1";

    private static final Pattern TOC_HEADING = Pattern.compile(
            "^(table of contents|contents|विषय[\\s-]?सूची|अनुक्रमणिका|विषयानुक्रमणिका)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_HEADING = Pattern.compile(
            "^(index|subject index|name index|general index|शब्दानुक्रमणिका|शब्द[\\s-]?सूची)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BACK_HEADING = Pattern.compile(
            "^(bibliography|references|works cited|glossary|appendix(?:\\s+[a-z0-9]+)?(?:[:.-].*)?|about the author"
                    + "|endnotes|notes|further reading|संदर्भ.*|ग्रंथ[\\s-]?सूची|परिशिष्ट.*|लेखक परिचय)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FRONT_HEADING = Pattern.compile(
            "^(foreword|preface|acknowledg(?:e)?ments?|dedication|प्रस्तावना|भूमिका|आभार|समर्पण)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FRONT_KEYWORDS = Pattern.compile(
            "all rights reserved|isbn|first published|printed in|library of congress|copyright\\s*(?:©|\\(c\\))"
                    + "|©\\s*\\d{4}|प्रकाशक|सर्वाधिकार",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOC_LINE = Pattern.compile("^.{3,100}?(?:\\.{2,}|\\s)\\s*[0-9\u0966-\u096F]{1,4}$");
    private static final Pattern INDEX_LINE =
            Pattern.compile("^.{2,60}?,\\s*[0-9\u0966-\u096F]{1,4}(?:[-–,\\s]+[0-9\u0966-\u096F]{1,4})*$");
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "^(?:(?:chapter|part|section|book|canto)\\s+(?:[0-9ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten)"
                    + "\\b.{0,70}|(?:अध्याय|प्रकरण|खंड|खण्ड|भाग)\\s+[0-9\u0966-\u096F\\p{L}]+.{0,70})$",
            Pattern.CASE_INSENSITIVE);

    private final TextCleaningService cleaning;
    private final int targetTokens;
    private final int overlapTokens;

    @Autowired
    public ChunkingService(TextCleaningService cleaning, BookBuddyProperties properties) {
        this(cleaning, properties.ingestion().chunkTokens(), properties.ingestion().chunkOverlapTokens());
    }

    public ChunkingService(TextCleaningService cleaning, int targetTokens, int overlapTokens) {
        this.cleaning = cleaning;
        this.targetTokens = Math.max(20, targetTokens);
        this.overlapTokens = Math.max(0, Math.min(overlapTokens, this.targetTokens / 2));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Token estimation
    // ---------------------------------------------------------------------------------------------------------

    /** Cheap token estimate: 4 chars per token for Latin text, ~2.5 for Devanagari. Never less than 1. */
    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            tokens += (c >= '\u0900' && c <= '\u097F') ? 0.4 : 0.25;
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Section classification
    // ---------------------------------------------------------------------------------------------------------

    /** Heuristic section type per page (same order as {@code pages}). */
    public List<SectionType> classifyPages(List<PageText> pages) {
        int total = pages.size();
        List<SectionType> result = new ArrayList<>(total);
        boolean backStarted = false;
        for (int i = 0; i < total; i++) {
            PageText page = pages.get(i);
            List<String> lines = nonBlankLines(page.rawText());
            double position = total <= 1 ? 0.5 : (double) i / (total - 1);
            boolean early = position <= 0.25 || i < 12;
            boolean late = position >= 0.6;
            SectionType type = SectionType.BODY;

            String headLine = headingOf(lines);
            if (late && headLine != null && (BACK_HEADING.matcher(headLine).matches()
                    || INDEX_HEADING.matcher(headLine).matches())) {
                backStarted = true;
            }
            if (backStarted) {
                type = SectionType.BACK_MATTER;
            } else if (late && lines.size() >= 6 && ratio(lines, INDEX_LINE) >= 0.5) {
                backStarted = true; // index pages are always at the very end
                type = SectionType.BACK_MATTER;
            } else if (early) {
                boolean tocHeading = headLine != null && TOC_HEADING.matcher(headLine).matches();
                boolean tocLike = lines.size() >= 6 && ratio(lines, TOC_LINE) >= 0.6;
                boolean frontHeading = headLine != null && FRONT_HEADING.matcher(headLine).matches();
                boolean legal = i < 10 && FRONT_KEYWORDS.matcher(page.text()).find() && page.text().length() < 2500;
                if (tocHeading || tocLike || frontHeading || legal) {
                    type = SectionType.FRONT_MATTER;
                }
            }
            result.add(type);
        }
        return result;
    }

    private static String headingOf(List<String> lines) {
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            String line = lines.get(i).strip();
            if (line.length() <= 60) {
                // only treat it as a heading candidate when it is short; the caller matches the patterns
                if (TOC_HEADING.matcher(line).matches() || INDEX_HEADING.matcher(line).matches()
                        || BACK_HEADING.matcher(line).matches() || FRONT_HEADING.matcher(line).matches()) {
                    return line;
                }
            }
        }
        return null;
    }

    private static double ratio(List<String> lines, Pattern p) {
        if (lines.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String l : lines) {
            if (p.matcher(l).matches()) {
                hits++;
            }
        }
        return (double) hits / lines.size();
    }

    private static List<String> nonBlankLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String l : text.split("\n")) {
            if (!l.isBlank()) {
                out.add(l.strip());
            }
        }
        return out;
    }

    private static List<String> chapterTitles(List<PageText> pages) {
        List<String> titles = new ArrayList<>(pages.size());
        String carried = null;
        for (PageText page : pages) {
            List<String> lines = nonBlankLines(page.rawText());
            String title = carried;
            String last = null;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.length() <= 90 && CHAPTER_HEADING.matcher(l).matches()) {
                    if (i < 5 && title == carried) {
                        title = l;
                    }
                    last = l;
                }
            }
            titles.add(title);
            carried = last != null ? last : title;
        }
        return titles;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Chunking
    // ---------------------------------------------------------------------------------------------------------

    private record Segment(
            String text, int pageStart, int pageEnd, int tokens, SectionType section, String chapter) {}

    /** Chunks cleaned pages. {@code pages} must be in order; empty pages are skipped. */
    public List<TextChunk> chunk(List<PageText> pages) {
        List<Segment> segments = buildSegments(pages);
        List<TextChunk> chunks = new ArrayList<>();
        int n = segments.size();
        int start = 0;
        while (start < n) {
            int tokens = 0;
            int end = start;
            while (end < n) {
                Segment s = segments.get(end);
                if (end > start && (s.section() != segments.get(start).section() || tokens + s.tokens() > targetTokens)) {
                    break;
                }
                tokens += s.tokens();
                end++;
            }
            emit(chunks, segments, start, end);
            if (end >= n) {
                break;
            }

            Segment next = segments.get(end);
            int newStart = end;
            int overlap = 0;
            while (newStart - 1 > start
                    && segments.get(newStart - 1).section() == next.section()
                    && overlap + segments.get(newStart - 1).tokens() <= overlapTokens) {
                overlap += segments.get(newStart - 1).tokens();
                newStart--;
            }
            if (overlap + next.tokens() > targetTokens) {
                newStart = end;
            }
            start = newStart;
        }
        return chunks;
    }

    private void emit(List<TextChunk> chunks, List<Segment> segments, int from, int to) {
        StringBuilder sb = new StringBuilder();
        int pageStart = Integer.MAX_VALUE;
        int pageEnd = Integer.MIN_VALUE;
        int tokens = 0;
        for (int i = from; i < to; i++) {
            Segment s = segments.get(i);
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s.text());
            pageStart = Math.min(pageStart, s.pageStart());
            pageEnd = Math.max(pageEnd, s.pageEnd());
            tokens += s.tokens();
        }
        String content = sb.toString().strip();
        if (content.isEmpty()) {
            return;
        }
        Segment last = segments.get(to - 1);
        chunks.add(new TextChunk(
                chunks.size(),
                content,
                pageStart,
                pageEnd,
                cleaning.detectLanguage(content).code(),
                tokens,
                last.section(),
                last.chapter()));
    }

    private List<Segment> buildSegments(List<PageText> pages) {
        List<SectionType> sections = classifyPages(pages);
        List<String> chapters = chapterTitles(pages);
        List<Segment> segments = new ArrayList<>();
        int pageFirstSegment = 0;
        for (int i = 0; i < pages.size(); i++) {
            PageText page = pages.get(i);
            List<String> sentences = cleaning.splitSentences(page.text());
            if (sentences.isEmpty()) {
                continue;
            }
            pageFirstSegment = segments.size();
            SectionType section = sections.get(i);
            String chapter = chapters.get(i);
            for (String sentence : sentences) {
                addSentence(segments, sentence, page.pageNumber(), section, chapter);
            }

            // A sentence that runs across a page break: glue the dangling tail of the previous page to this page's head.
            if (pageFirstSegment > 0 && pageFirstSegment < segments.size()) {
                Segment prev = segments.get(pageFirstSegment - 1);
                Segment head = segments.get(pageFirstSegment);
                if (prev.pageEnd() < head.pageStart() && prev.section() == head.section()
                        && !endsSentence(prev.text()) && startsContinuation(head.text())
                        && prev.tokens() + head.tokens() <= targetTokens) {
                    String text = prev.text() + " " + head.text();
                    segments.set(pageFirstSegment - 1, new Segment(
                            text, prev.pageStart(), head.pageEnd(), estimateTokens(text), prev.section(), prev.chapter()));
                    segments.remove(pageFirstSegment);
                }
            }
        }
        return segments;
    }

    private void addSentence(
            List<Segment> out, String sentence, int page, SectionType section, String chapter) {
        int tokens = estimateTokens(sentence);
        if (tokens <= targetTokens) {
            out.add(new Segment(sentence, page, page, tokens, section, chapter));
            return;
        }
        // Over-long "sentence" (no punctuation, tables, OCR noise): split on whitespace as a last resort.
        StringBuilder piece = new StringBuilder();
        for (String word : sentence.split(" ")) {
            if (piece.length() > 0 && estimateTokens(piece + " " + word) > targetTokens) {
                String text = piece.toString();
                out.add(new Segment(text, page, page, estimateTokens(text), section, chapter));
                piece.setLength(0);
            }
            if (piece.length() > 0) {
                piece.append(' ');
            }
            piece.append(word);
        }
        if (piece.length() > 0) {
            String text = piece.toString();
            out.add(new Segment(text, page, page, estimateTokens(text), section, chapter));
        }
    }

    private static boolean endsSentence(String s) {
        String t = s.stripTrailing();
        if (t.isEmpty()) {
            return true;
        }
        char c = t.charAt(t.length() - 1);
        return c == '.' || c == '?' || c == '!' || c == '\u0964' || c == '\u0965' || c == '"' || c == '\u201D'
                || c == ')' || c == ':';
    }

    private static boolean startsContinuation(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int cp = s.codePointAt(0);
        return Character.isLowerCase(cp);
    }
}
