package com.bookbuddy.document;

/**
 * Text of a single PDF page.
 *
 * @param pageNumber 1-based page number
 * @param text cleaned (or, straight out of extraction, raw) text with hard-wrapped lines merged
 * @param rawText text with header/footer noise removed but original line structure kept; used by heuristics that
 *     need line layout (table of contents, index, chapter headings)
 */
public record PageText(int pageNumber, String text, String rawText) {

    public static PageText raw(int pageNumber, String text) {
        return new PageText(pageNumber, text, text);
    }
}
