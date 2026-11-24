package com.bookbuddy.memory;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Detects natural-language forget / correct / stop-remembering requests in chat. */
@Component
public class MemoryControlDetector {

    public enum Kind {
        NONE,
        FORGET,
        CORRECT
    }

    public record Detection(Kind kind, String topic) {
        public static final Detection NONE = new Detection(Kind.NONE, "");

        public boolean isControl() {
            return kind != Kind.NONE;
        }
    }

    private static final Pattern FORGET = Pattern.compile(
            "(?i)\\b(?:forget|don'?t remember|do not remember|stop remembering|delete that|remove that|"
                    + "erase that|forget about|please forget|never remember)\\b");

    private static final Pattern CORRECT = Pattern.compile(
            "(?i)\\b(?:that'?s wrong|that is wrong|that'?s not (?:true|right|correct)|"
                    + "actually[, ]|i (?:meant|said)|correct that|update that|change that memory)\\b");

    public Detection detect(String text) {
        if (text == null || text.isBlank()) {
            return Detection.NONE;
        }
        String t = text.strip();
        if (FORGET.matcher(t).find()) {
            return new Detection(Kind.FORGET, topicAfter(t, FORGET));
        }
        if (CORRECT.matcher(t).find()) {
            return new Detection(Kind.CORRECT, topicAfter(t, CORRECT));
        }
        return Detection.NONE;
    }

    private static String topicAfter(String text, Pattern cue) {
        var m = cue.matcher(text);
        if (!m.find()) {
            return text.toLowerCase(Locale.ROOT);
        }
        String after = text.substring(m.end()).replaceAll("^[\\s:,-]+", "").strip();
        if (after.length() < 2) {
            return text.toLowerCase(Locale.ROOT);
        }
        return after.length() > 120 ? after.substring(0, 120) : after;
    }
}
