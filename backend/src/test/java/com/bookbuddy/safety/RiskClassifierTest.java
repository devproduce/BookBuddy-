package com.bookbuddy.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookbuddy.ai.LlmException;
import com.bookbuddy.ai.LlmProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RiskClassifierTest {

    private final LlmProvider llm = mock(LlmProvider.class);
    private final RiskClassifier classifier = new RiskClassifier(llm);

    @ParameterizedTest
    @ValueSource(strings = {
        "I want to kill myself",
        "i wanna die",
        "I just want to die.",
        "I'm going to end my life tonight",
        "I've been thinking about suicide a lot",
        "I feel suicidal",
        "I don\u2019t want to live anymore",
        "I'm thinking of ending it all",
        "Everyone would be better off if I was dead. I wish I was dead",
        "I want to hurt myself",
        "I have a plan to kill myself",
        "i am going to commit suicide",
        "I'm going to take my own life",
        "KILL MYSELF",
        "I wrote a suicide note",
    })
    void englishAcutePhrases(String text) {
        assertEquals(RiskLevel.ACUTE, classifier.classifyByRules(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\u092E\u0948\u0902 \u0906\u0924\u094D\u092E\u0939\u0924\u094D\u092F\u093E \u0915\u0930\u0928\u093E \u091A\u093E\u0939\u0924\u093E \u0939\u0942\u0901",
        "\u092E\u0941\u091D\u0947 \u092E\u0930\u0928\u093E \u0939\u0948",
        "\u092E\u0948\u0902 \u091C\u0940\u0928\u093E \u0928\u0939\u0940\u0902 \u091A\u093E\u0939\u0924\u0940",
        "\u092E\u0948\u0902 \u092E\u0930\u0928\u093E \u091A\u093E\u0939\u0924\u093E \u0939\u0942\u0901",
        "\u092E\u0948\u0902 \u0916\u0941\u0926\u0915\u0941\u0936\u0940 \u0915\u0930 \u0932\u0942\u0902\u0917\u093E",
        "\u0905\u092C \u092E\u0948\u0902 \u0905\u092A\u0928\u0940 \u091C\u093E\u0928 \u0932\u0942\u0902\u0917\u093E \u0924\u094B \u0905\u091A\u094D\u091B\u093E \u0939\u094B\u0917\u093E. \u092E\u0948\u0902 \u0905\u092A\u0928\u0940 \u091C\u093E\u0928 \u0926\u0947 \u0926\u0942\u0902\u0917\u093E",
        "\u0932\u0917\u0924\u093E \u0939\u0948 \u092E\u0948\u0902 \u0916\u0941\u0926 \u0915\u094B \u0916\u0924\u094D\u092E \u0915\u0930 \u0926\u0942\u0902",
        "\u092E\u0948\u0902 \u091C\u0940\u0928\u093E \u0928\u0939\u0940\u0902 \u091A\u093E\u0939\u0924\u093E",
        "\u092E\u0948\u0902 \u092E\u0930 \u091C\u093E\u0928\u093E \u091A\u093E\u0939\u0924\u093E \u0939\u0942\u0901",
    })
    void hindiDevanagariAcutePhrases(String text) {
        assertEquals(RiskLevel.ACUTE, classifier.classifyByRules(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "mujhe marna hai",
        "Main marna chahta hu",
        "mai marna chahti hoon",
        "main jeena nahi chahta",
        "mujhe jeena nahi hai",
        "mujhe khudkushi karni hai, main khudkushi karna chahta hu",
        "main suicide karna chahta hu",
        "mai apni jaan le lunga",
        "ab main khud ko khatam kar dunga",
        "main zindagi khatam kar dunga",
        "mar jaunga main",
        "mujhe aatmhatya ke vichar aa rahe hain",
    })
    void hinglishAcutePhrases(String text) {
        assertEquals(RiskLevel.ACUTE, classifier.classifyByRules(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "I feel so hopeless lately",
        "I can't go on like this",
        "I am tired of living",
        "I hate myself",
        "I've been cutting myself",
        "nobody cares about me",
        "\u092E\u0948\u0902 \u092C\u0939\u0941\u0924 \u0909\u0926\u093E\u0938 \u0939\u0942\u0901",
        "\u0915\u094B\u0908 \u0909\u092E\u094D\u092E\u0940\u0926 \u0928\u0939\u0940\u0902 \u0939\u0948",
        "main bahut udaas hoon",
        "koi umeed nahi hai",
        "I would never kill myself, but I feel so tired",
        "I don't want to die",
    })
    void elevatedPhrases(String text) {
        assertEquals(RiskLevel.ELEVATED, classifier.classifyByRules(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Why does Anna Karenina commit suicide at the end?",
        "How does the author portray Werther's suicide?",
        "Summarize chapter 3, where she wants to die in the snow",
        "He wanted to die, and the narrator never explains why",
        "What does the book say about hope?",
        "I'm dying to know what happens in chapter 5",
        "I want to die of embarrassment, that scene was so awkward",
        "Tell me about the themes of death in this novel",
        "Hello there!",
        "I love this book so much",
        "\u0907\u0938 \u0915\u093F\u0924\u093E\u092C \u0915\u093E \u092E\u0941\u0916\u094D\u092F \u0915\u093F\u0930\u0926\u093E\u0930 \u0915\u094C\u0928 \u0939\u0948?",
        "is kitab ka ending kya hai?",
        "usne khudkushi kyun ki is kahani mein?",
        "",
    })
    void benignMessagesAreNotFlagged(String text) {
        assertEquals(RiskLevel.NONE, classifier.classifyByRules(text), text);
    }

    @Test
    void nullIsNone() {
        assertEquals(RiskLevel.NONE, classifier.classifyByRules(null));
        assertEquals(RiskLevel.NONE, classifier.classify(null));
    }

    @Test
    void devanagariSpellingVariantsNormalise() {
        // with and without the nukta (\u0916\u093C vs \u0916) and chandrabindu (\u0901 vs \u0902)
        assertEquals(RiskLevel.ACUTE, classifier.classifyByRules("\u092E\u0948\u0902 \u0906\u0924\u094D\u092E\u0939\u0924\u094D\u092F\u093E \u0915\u0930\u0942\u0902\u0917\u093E"));
        assertEquals(RiskLevel.ACUTE, classifier.classifyByRules("\u092E\u0948\u0902 \u0916\u093C\u0941\u0926\u0915\u0941\u0936\u0940 \u0915\u0930\u0942\u0902\u0917\u093E"));
    }

    @Test
    void acuteRulesNeverCallTheLlm() {
        when(llm.isConfigured()).thenReturn(true);
        assertEquals(RiskLevel.ACUTE, classifier.classify("I want to kill myself"));
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void llmCanEscalateDistressedMessages() {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("ACUTE");
        assertEquals(RiskLevel.ACUTE, classifier.classify("everything feels pointless and I am so alone"));
    }

    @Test
    void llmIsSkippedForMessagesWithNoDistressSignal() {
        when(llm.isConfigured()).thenReturn(true);
        assertEquals(RiskLevel.NONE, classifier.classify("Who wrote the lighthouse keeper's diary?"));
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void llmCannotDowngradeRuleResultAndFailuresFallBackToRules() {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("NONE");
        assertEquals(RiskLevel.ELEVATED, classifier.classify("I feel so hopeless lately"));

        when(llm.complete(anyString(), anyString())).thenThrow(new LlmException("down"));
        assertEquals(RiskLevel.ELEVATED, classifier.classify("I feel so hopeless lately"));
        assertEquals(RiskLevel.NONE, classifier.classify("I am so alone in this town and it's lonely"));
    }
}