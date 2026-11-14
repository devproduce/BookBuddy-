package com.bookbuddy.safety;

/** Self-harm risk of a user message. Declared in ascending severity so {@link #max} can use ordinal order. */
public enum RiskLevel {
    NONE,
    /** Signs of distress: continue the conversation, but gently and with resources on offer. */
    ELEVATED,
    /** Explicit intent / ideation: skip retrieval and personalisation and show the crisis response. */
    ACUTE;

    public static RiskLevel max(RiskLevel a, RiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
