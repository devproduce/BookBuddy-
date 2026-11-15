package com.bookbuddy.memory;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic detector for the sensitive categories that must never be <em>inferred</em> about a person: health and
 * mental health, sexuality, religion, politics, finances, relationships and legal matters (PRD 8.5). It deliberately
 * errs on the side of caution: a false positive only means a memory is stored when the user states it explicitly (and
 * flagged SENSITIVE) instead of being guessed.
 */
public final class SensitiveTopics {

    private SensitiveTopics() {}

    public enum Topic {
        HEALTH,
        SEXUALITY,
        RELIGION,
        POLITICS,
        FINANCES,
        RELATIONSHIPS,
        LEGAL
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static Pattern words(String alternatives) {
        return Pattern.compile("(?<![\\p{L}])(?:" + alternatives + ")(?![\\p{L}])", FLAGS);
    }

    private static final Pattern HEALTH = words("diagnos\\w*|illness|disease|disorder|cancer|diabet\\w*|asthma|epilep\\w*|"
            + "surgery|chemo\\w*|medicat\\w*|prescri\\w*|antidepress\\w*|pills?|therap(?:y|ist|ists)|psychiatr\\w*|"
            + "psycholog\\w*|counsel(?:l)?ing|counsel(?:l)?or|adhd|autis\\w*|bipolar|schizo\\w*|ocd|ptsd|anxiety|"
            + "depress\\w*|panic attacks?|eating disorder|anorex\\w*|bulimi\\w*|self[- ]?harm\\w*|suicid\\w*|"
            + "disabilit\\w*|disabled|pregnan\\w*|miscarriage|hiv|insomnia|migraines?|addict\\w*|alcohol\\w*|rehab|"
            + "mental health|trauma\\w*|chronic (?:pain|illness|condition)");

    private static final Pattern SEXUALITY = words("gay|lesbian|bisexual|queer|lgbt\\w*|transgender|trans (?:man|woman)|"
            + "asexual|pansexual|sexual\\w*|sexuality|coming out|homosexual|heterosexual|porn\\w*|kink\\w*|gender identity");

    private static final Pattern RELIGION = words("relig\\w*|god|gods|allah|jesus|christ\\w*|muslim\\w*|islam\\w*|hindu\\w*|"
            + "buddhis\\w*|sikh\\w*|jewish|judaism|atheis\\w*|agnostic\\w*|church|mosque|temple|prayers?|pray(?:s|ed|ing)?|"
            + "faith|spiritual\\w*|bible|quran|koran|bhagavad|worship\\w*");

    private static final Pattern POLITICS = words("politic\\w*|democrat\\w*|republican\\w*|liberals?|conservatives?|bjp|"
            + "congress party|modi|trump|biden|elections?|vote|voted|voting|left[- ]wing|right[- ]wing|socialis\\w*|"
            + "communis\\w*|marxis\\w*|fascis\\w*|nationalis\\w*|activis\\w*|protest\\w*");

    private static final Pattern FINANCES = words("salary|income|debts?|loans?|mortgage|bankrupt\\w*|credit (?:card|score)|"
            + "savings|invest\\w*|stocks?|crypto\\w*|net worth|poverty|wealth\\w*|taxes|tax|financ\\w*|money|afford\\w*|"
            + "paycheck|rupees|dollars|unemployed|laid off|salaried");

    private static final Pattern RELATIONSHIPS = words("girlfriends?|boyfriends?|husbands?|wife|wives|spouse|partner|"
            + "fianc\\w*|divorc\\w*|break ?ups?|broke up|dating|ex[- ](?:girlfriend|boyfriend|wife|husband|partner)|"
            + "married|marriage|affair|cheat\\w*|crush|relationships?|widow\\w*|separated|romantic|in love|toxic");

    private static final Pattern LEGAL = words("lawsuit|sued|sue|court|lawyers?|attorney|legal\\w*|arrest\\w*|convict\\w*|"
            + "criminal|police|prison|jail|custody|immigration|visa|asylum|probation|sentenc\\w*|restraining order|"
            + "litigation");

    /** Devanagari (no word boundaries: {@code \b} is unreliable for combining marks). */
    private static final Pattern HINDI_HEALTH = Pattern.compile("बीमारी|डिप्रेशन|इलाज|दवा|अवसाद|चिंता रोग|कैंसर|मानसिक");
    private static final Pattern HINDI_RELIGION = Pattern.compile("धर्म|भगवान|ईश्वर|अल्लाह|मंदिर|मस्जिद|चर्च|पूजा|नमाज");
    private static final Pattern HINDI_POLITICS = Pattern.compile("राजनीति|चुनाव|वोट|भाजपा|कांग्रेस");
    private static final Pattern HINDI_FINANCES = Pattern.compile("कर्ज|सैलरी|वेतन|पैसे|आमदनी|लोन");
    private static final Pattern HINDI_RELATIONSHIPS = Pattern.compile("गर्लफ्रेंड|बॉयफ्रेंड|शादी|तलाक|पति|पत्नी|रिश्ता|प्रेमी|प्रेमिका");
    private static final Pattern HINDI_LEGAL = Pattern.compile("मुकदमा|अदालत|वकील|पुलिस|जेल|गिरफ्तार");

    /** Topics mentioned in {@code text}; empty when the text looks harmless. */
    public static Set<Topic> detect(String text) {
        Set<Topic> found = EnumSet.noneOf(Topic.class);
        if (text == null || text.isBlank()) {
            return found;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (HEALTH.matcher(t).find() || HINDI_HEALTH.matcher(t).find()) {
            found.add(Topic.HEALTH);
        }
        if (SEXUALITY.matcher(t).find()) {
            found.add(Topic.SEXUALITY);
        }
        if (RELIGION.matcher(t).find() || HINDI_RELIGION.matcher(t).find()) {
            found.add(Topic.RELIGION);
        }
        if (POLITICS.matcher(t).find() || HINDI_POLITICS.matcher(t).find()) {
            found.add(Topic.POLITICS);
        }
        if (FINANCES.matcher(t).find() || HINDI_FINANCES.matcher(t).find()) {
            found.add(Topic.FINANCES);
        }
        if (RELATIONSHIPS.matcher(t).find() || HINDI_RELATIONSHIPS.matcher(t).find()) {
            found.add(Topic.RELATIONSHIPS);
        }
        if (LEGAL.matcher(t).find() || HINDI_LEGAL.matcher(t).find()) {
            found.add(Topic.LEGAL);
        }
        return found;
    }

    public static boolean isSensitive(String text) {
        return !detect(text).isEmpty();
    }
}
