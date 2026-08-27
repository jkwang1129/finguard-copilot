package com.finguard.copilot.knowledge.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public final class DocumentCleaner {

    public CanonicalDocument canonicalize(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("rawText must not be blank");
        }

        String withoutBom = rawText.startsWith("\uFEFF") ? rawText.substring(1) : rawText;
        String lineEndings = withoutBom.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder normalized = new StringBuilder(lineEndings.length());
        boolean pendingSpace = false;
        for (int index = 0; index < lineEndings.length(); index++) {
            char character = lineEndings.charAt(index);
            if (character == '\n') {
                trimTrailingSpaces(normalized);
                normalized.append('\n');
                pendingSpace = false;
            } else if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
                pendingSpace = true;
            } else {
                if (pendingSpace && normalized.length() > 0
                        && normalized.charAt(normalized.length() - 1) != '\n') {
                    normalized.append(' ');
                }
                normalized.append(character);
                pendingSpace = false;
            }
        }
        trimTrailingSpaces(normalized);

        List<String> lines = new ArrayList<>();
        for (String line : normalized.toString().split("\\n", -1)) {
            lines.add(line.trim());
        }
        while (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        String canonical = String.join("\n", lines);
        if (canonical.isBlank()) {
            throw new IllegalArgumentException("rawText must contain visible text");
        }
        return new CanonicalDocument(canonical, sha256(canonical));
    }

    private static void trimTrailingSpaces(StringBuilder value) {
        while (!value.isEmpty() && value.charAt(value.length() - 1) == ' ') {
            value.deleteCharAt(value.length() - 1);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
