package com.bookbuddy.vector;

import com.bookbuddy.document.TextTokens;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Lexical search over chunk text: a case-insensitive regex on {@code content} fetches candidates in MongoDB (no text
 * index needed, works for Hindi too) and they are ranked in-process by log-scaled term frequency.
 */
@Component
@RequiredArgsConstructor
public class KeywordSearchRepository {

    private static final int MAX_KEYWORDS = 8;
    private static final int MAX_CANDIDATES = 400;

    private final MongoTemplate mongoTemplate;

    public List<ScoredChunk> search(String userId, String queryText, Collection<String> bookIds, int topN) {
        List<String> keywords = keywords(queryText);
        if (keywords.isEmpty() || topN <= 0) {
            return List.of();
        }
        String regex = String.join("|", keywords.stream().map(Pattern::quote).toList());
        Criteria criteria = Criteria.where("userId").is(userId).and("content").regex(regex, "i");
        if (bookIds != null && !bookIds.isEmpty()) {
            criteria = criteria.and("bookId").in(bookIds);
        }
        Query query = Query.query(criteria).limit(MAX_CANDIDATES);
        query.fields().exclude("embedding");
        return rank(mongoTemplate.find(query, BookChunkDocument.class), keywords, topN);
    }

    static List<String> keywords(String queryText) {
        Set<String> out = new LinkedHashSet<>(TextTokens.contentTokens(queryText));
        return out.stream().limit(MAX_KEYWORDS).toList();
    }

    static List<ScoredChunk> rank(List<BookChunkDocument> candidates, List<String> keywords, int topN) {
        List<ScoredChunk> scored = new ArrayList<>();
        for (BookChunkDocument c : candidates) {
            String text = c.getContent() == null ? "" : c.getContent().toLowerCase(Locale.ROOT);
            double score = 0;
            for (String kw : keywords) {
                int tf = countOccurrences(text, kw);
                if (tf > 0) {
                    score += 1 + Math.log(tf);
                }
            }
            if (score > 0) {
                // mild length normalisation so short, dense passages beat long, diluted ones
                score /= Math.sqrt(Math.max(50, text.length()) / 400.0);
                scored.add(new ScoredChunk(c, score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored.size() > topN ? new ArrayList<>(scored.subList(0, topN)) : scored;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
