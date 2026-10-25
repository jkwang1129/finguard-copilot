package com.finguard.copilot.knowledge.ingest;

import java.util.regex.Pattern;

public final class KnowledgeAdmissionPolicy {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(authorization\\s*:\\s*bearer|password\\s*[=:]|secret\\s*[=:]|api[_-]?key\\s*[=:]|-----begin)");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern DYNAMIC_CASE_FIELD = Pattern.compile(
            "(?i)\\b(transaction[_-]?id|review[_-]?task[_-]?id|review[_-]?status|amount|currency)\\b\\s*[:=]");

    public void check(KnowledgeDocumentRequest request, CanonicalDocument document) {
        if (!request.sourceType().admissible()) {
            throw new IllegalArgumentException("sourceType is not admitted to the knowledge base");
        }
        String content = document.text();
        if (SECRET.matcher(content).find() || JWT.matcher(content).find()) {
            throw rejection("content contains a secret or token");
        }
        if (URL.matcher(content).find()) {
            throw rejection("content contains an arbitrary URL");
        }
        if (DYNAMIC_CASE_FIELD.matcher(content).find()) {
            throw rejection("content contains unsanitized case fields");
        }
    }

    private static IllegalArgumentException rejection(String reason) {
        return new IllegalArgumentException("knowledge admission rejected: " + reason);
    }
}
