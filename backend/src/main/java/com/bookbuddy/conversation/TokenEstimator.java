package com.bookbuddy.conversation;

/** Rough token estimate (about 4 characters per token for Latin text, 1.5 for other scripts). */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int latin = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 0x250) {
                latin++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(latin / 4.0 + other / 1.5);
    }
}