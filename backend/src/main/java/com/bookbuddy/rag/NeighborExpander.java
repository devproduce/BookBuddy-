package com.bookbuddy.rag;

import com.bookbuddy.vector.BookChunkDocument;
import java.util.Optional;

/**
 * Chunks are cut by token count, so a retrieved chunk can begin or end in the middle of a sentence. This borrows the
 * missing words from the neighbouring chunk (same book, same section) so the model, and the reader opening the
 * citation, see whole sentences. Neighbours overlap by design; the overlap is detected and not repeated.
 */
final class NeighborExpander {

    /** Finds the chunk at {@code index} of {@code bookId}, if any. */
    @FunctionalInterface
    interface Lookup {
        Optional<BookChunkDocument> find(String bookId, int index);
    }

    private static final String TERMINATORS = ".!?\u2026\u0964\u061F";
    private static final String CLOSERS = "\"'\u201D\u2019)]}\u00BB*";
    private static final int MIN_OVERLAP = 15;
    private static final int MAX_OVERLAP_SCAN = 1500;

    private NeighborExpander() {}

    /** True when the text stops without finishing its last sentence. */
    static boolean endsTruncated(String text) {
        if (text == null) {
            return false;
        }
        int i = text.length() - 1;
        while (i >= 0 && (Character.isWhitespace(text.charAt(i)) || CLOSERS.indexOf(text.charAt(i)) >= 0)) {
            i--;
        }
        return i >= 0 && TERMINATORS.indexOf(text.charAt(i)) < 0;
    }

    /** True when the text starts in the middle of a sentence (a lower-case letter or closing punctuation). */
    static boolean startsTruncated(String text) {
        if (text == null) {
            return false;
        }
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length()) {
            return false;
        }
        char c = text.charAt(i);
        return Character.isLowerCase(c) || ",;:)]}".indexOf(c) >= 0;
    }

    /**
     * Returns {@code chunk} itself when it needs nothing (or no neighbour is available), otherwise a copy with the
     * missing sentence fragments added and the page range widened to match.
     */
    static BookChunkDocument expand(BookChunkDocument chunk, Lookup lookup, int maxChars) {
        String content = chunk.getContent();
        if (content == null || content.isBlank() || chunk.getBookId() == null) {
            return chunk;
        }
        boolean needTail = endsTruncated(content);
        boolean needHead = startsTruncated(content);
        if (!needTail && !needHead) {
            return chunk;
        }

        String text = content.strip();
        Integer pageStart = chunk.getPageStart();
        Integer pageEnd = chunk.getPageEnd();
        boolean changed = false;

        if (needTail) {
            BookChunkDocument next = neighbour(chunk, chunk.getChunkIndex() + 1, lookup);
            if (next != null) {
                String grown = appendFromNext(text, next.getContent().strip(), maxChars);
                if (grown.length() > text.length()) {
                    text = grown;
                    changed = true;
                    if (next.getPageEnd() != null && (pageEnd == null || next.getPageEnd() > pageEnd)) {
                        pageEnd = next.getPageEnd();
                    }
                }
            }
        }
        if (needHead && chunk.getChunkIndex() > 0) {
            BookChunkDocument prev = neighbour(chunk, chunk.getChunkIndex() - 1, lookup);
            if (prev != null) {
                String grown = prependFromPrevious(text, prev.getContent().strip(), maxChars);
                if (grown.length() > text.length()) {
                    text = grown;
                    changed = true;
                    if (prev.getPageStart() != null && (pageStart == null || prev.getPageStart() < pageStart)) {
                        pageStart = prev.getPageStart();
                    }
                }
            }
        }
        if (!changed) {
            return chunk;
        }

        BookChunkDocument copy = new BookChunkDocument();
        copy.setId(chunk.getId());
        copy.setUserId(chunk.getUserId());
        copy.setBookId(chunk.getBookId());
        copy.setChunkIndex(chunk.getChunkIndex());
        copy.setContent(text);
        copy.setPageStart(pageStart);
        copy.setPageEnd(pageEnd);
        copy.setChapterTitle(chunk.getChapterTitle());
        copy.setLanguage(chunk.getLanguage());
        copy.setTokenCount(chunk.getTokenCount());
        copy.setChunkingVersion(chunk.getChunkingVersion());
        copy.setEmbeddingVersion(chunk.getEmbeddingVersion());
        copy.setEmbeddingModel(chunk.getEmbeddingModel());
        copy.setSectionType(chunk.getSectionType());
        copy.setCreatedAt(chunk.getCreatedAt());
        return copy;
    }

    private static BookChunkDocument neighbour(BookChunkDocument chunk, int index, Lookup lookup) {
        Optional<BookChunkDocument> found = lookup.find(chunk.getBookId(), index);
        if (found == null || found.isEmpty()) {
            return null;
        }
        BookChunkDocument n = found.get();
        if (n.getContent() == null || n.getContent().isBlank() || n.getSectionType() != chunk.getSectionType()) {
            return null;
        }
        return n;
    }

    /** {@code current} + the rest of its last sentence taken from {@code next} (overlap skipped). */
    static String appendFromNext(String current, String next, int maxChars) {
        int overlap = overlap(current, next);
        String rest = next.substring(overlap).stripLeading();
        if (rest.isEmpty()) {
            return current;
        }
        return current + " " + firstSentence(rest, maxChars);
    }

    /** The beginning of the first sentence of {@code current}, taken from {@code previous}, + {@code current}. */
    static String prependFromPrevious(String current, String previous, int maxChars) {
        int overlap = overlap(previous, current);
        String head = previous.substring(0, previous.length() - overlap).stripTrailing();
        if (head.isEmpty() || !endsTruncated(head)) {
            return current; // the previous chunk ended a sentence: nothing is missing
        }
        String piece = lastSentenceFragment(head, maxChars);
        return piece.isEmpty() ? current : piece + " " + current;
    }

    /** Length of the longest suffix of {@code a} that is also a prefix of {@code b} (0 if shorter than the minimum). */
    private static int overlap(String a, String b) {
        int max = Math.min(Math.min(a.length(), b.length()), MAX_OVERLAP_SCAN);
        for (int k = max; k >= MIN_OVERLAP; k--) {
            if (a.regionMatches(a.length() - k, b, 0, k)) {
                return k;
            }
        }
        return 0;
    }

    /** Up to the first sentence end (inclusive), at most {@code maxChars} long and cut at a word boundary. */
    private static String firstSentence(String text, int maxChars) {
        int limit = Math.min(text.length(), maxChars);
        for (int i = 0; i < limit; i++) {
            if (TERMINATORS.indexOf(text.charAt(i)) >= 0) {
                int end = i + 1;
                while (end < text.length() && CLOSERS.indexOf(text.charAt(end)) >= 0) {
                    end++;
                }
                if (end >= text.length() || Character.isWhitespace(text.charAt(end))) {
                    return text.substring(0, end);
                }
            }
        }
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf(' ', maxChars);
        return text.substring(0, cut > 0 ? cut : maxChars).stripTrailing();
    }

    /** Text after the last sentence end of {@code text}, or its last {@code maxChars} when that is too long. */
    private static String lastSentenceFragment(String text, int maxChars) {
        int start = 0;
        for (int i = text.length() - 2; i >= 0; i--) {
            if (TERMINATORS.indexOf(text.charAt(i)) >= 0 && Character.isWhitespace(text.charAt(i + 1))) {
                start = i + 1;
                break;
            }
        }
        String fragment = text.substring(start).strip();
        if (fragment.length() > maxChars) {
            int from = fragment.length() - maxChars;
            int space = fragment.indexOf(' ', from);
            fragment = space < 0 ? "" : fragment.substring(space + 1);
        }
        return fragment;
    }
}
