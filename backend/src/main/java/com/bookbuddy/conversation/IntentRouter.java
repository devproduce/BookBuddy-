package com.bookbuddy.conversation;

import com.bookbuddy.document.TextTokens;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Cheap heuristic router deciding whether a message needs book retrieval. Venting and small talk never trigger a
 * (slow, possibly irrelevant) library search.
 */
@Component
public class IntentRouter {

    public enum Intent {
        BOOK_QUESTION,
        PERSONAL_REFLECTION,
        MIXED,
        CHITCHAT;

        /**
         * Whether to search the user's library. Personal reflection still retrieves so Book Buddy can offer a
         * relevant passage when it helps; the system prompt forbids forcing books into pure venting.
         * Only chitchat skips retrieval entirely.
         */
        public boolean needsBooks() {
            return this != CHITCHAT;
        }
    }

    private static final Pattern GREETING = Pattern.compile(
            "^(?:hi+|hello+|hey+|heya|yo|hola|namaste|namaskar|good (?:morning|afternoon|evening|night)|gm|gn|"
                    + "thanks?|thank you|thx|ty|ok(?:ay)?|cool|nice|great|bye|goodbye|see you|how are you|how r u|"
                    + "what'?s up|sup|kya haal(?: hai)?|kaise ho|shukriya|dhanyavaad|dhanyawad|धन्यवाद|शुक्रिया|नमस्ते|"
                    + "नमस्कार|हैलो|हेलो)(?: there| buddy| book buddy)?$");

    private static final List<String> BOOK_CUES = List.of(
            "book", "books", "chapter", "chapters", "character", "characters", "author", "novel", "plot", "passage",
            "quote", "quotes", "page", "pages", "story", "stories", "protagonist", "antagonist", "theme", "themes",
            "ending", "scene", "summarize", "summarise", "summary", "according to", "in the book", "the author",
            "narrator", "symbolism", "metaphor", "wisdom", "lesson", "lessons", "philosophy", "teaching", "teachings",
            "what does it say", "what does the book", "from the book", "my library", "this book", "that book",
            "kitab", "kitaab", "किताब", "पुस्तक", "कहानी", "अध्याय", "किरदार", "पात्र", "लेखक", "सीख");

    private static final List<String> PERSONAL_CUES = List.of(
            "i feel", "i'm feeling", "im feeling", "i am feeling", "i felt", "i've been feeling", "ive been feeling",
            "i'm sad", "im sad", "i am sad", "i'm stressed", "im stressed", "i'm anxious", "im anxious",
            "i'm lonely", "im lonely", "i am lonely", "i'm tired", "im tired", "i'm overwhelmed", "im overwhelmed",
            "i'm upset", "im upset", "i'm angry", "im angry", "i'm worried", "im worried", "i'm scared", "im scared",
            "vent", "venting", "rough day", "bad day", "hard day", "my day", "my life", "my family", "my boss",
            "my job", "my mom", "my dad", "my partner", "my boyfriend", "my girlfriend", "my friend", "breakup",
            "break up", "heartbroken", "can't sleep", "cant sleep", "burnout", "burned out", "panic", "depressed", "depression",
            "lonely", "grief", "grieving", "mujhe", "mera mann", "mera man", "udaas", "dukhi", "pareshan",
            "tension", "akela", "akeli", "thak gaya", "thak gayi", "मुझे", "मेरा मन", "उदास", "दुखी", "परेशान",
            "अकेला", "अकेली", "थक गया", "थक गई", "तनाव");

    /** Routes {@code text}; {@code scopeOne} is true when the user pinned the chat to a specific book. */
    public Intent route(String text, boolean scopeOne) {
        String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return Intent.CHITCHAT;
        }
        String stripped = t.replaceAll("[\\p{Punct}\\s\\u0964]+$", "").replaceAll("^[\\p{Punct}\\s]+", "");
        boolean book = containsAny(t, BOOK_CUES);
        boolean personal = containsAny(t, PERSONAL_CUES);

        if (!book && !personal && GREETING.matcher(stripped).matches()) {
            return Intent.CHITCHAT;
        }
        if (book && personal) {
            return Intent.MIXED;
        }
        if (book) {
            return Intent.BOOK_QUESTION;
        }
        if (personal) {
            return Intent.PERSONAL_REFLECTION;
        }
        int words = TextTokens.tokenize(t).size();
        if (words <= 2 && !scopeOne) {
            return Intent.CHITCHAT;
        }
        // Unclear substantive messages default to the library — this is a reading companion.
        return Intent.BOOK_QUESTION;
    }

    private static boolean containsAny(String text, List<String> cues) {
        for (String cue : cues) {
            if (cue.codePoints().allMatch(c -> c < 128)) {
                // ASCII cues must match on word boundaries ("page" must not fire on "stage")
                if (Pattern.compile("(?<![a-z])" + Pattern.quote(cue) + "(?![a-z])").matcher(text).find()) {
                    return true;
                }
            } else if (text.contains(cue)) {
                return true;
            }
        }
        return false;
    }
}