package com.finguard.copilot.context;

@FunctionalInterface
public interface TokenEstimator {

    int estimate(String text);

    static TokenEstimator conservative() {
        return text -> {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            long asciiCodePoints = 0;
            long nonAsciiCodePoints = 0;
            for (int offset = 0; offset < text.length();) {
                int codePoint = text.codePointAt(offset);
                if (codePoint <= 0x7f) {
                    asciiCodePoints++;
                } else {
                    nonAsciiCodePoints++;
                }
                offset += Character.charCount(codePoint);
            }
            return Math.toIntExact((asciiCodePoints + 3L) / 4L + nonAsciiCodePoints);
        };
    }
}
