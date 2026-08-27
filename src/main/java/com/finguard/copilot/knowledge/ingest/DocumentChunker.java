package com.finguard.copilot.knowledge.ingest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.finguard.copilot.knowledge.domain.KnowledgeChunk;

public final class DocumentChunker {

    private final int maximumCharacters;
    private final int overlapCharacters;

    public DocumentChunker(int maximumCharacters, int overlapCharacters) {
        if (maximumCharacters < 16) {
            throw new IllegalArgumentException("maximumCharacters must be at least 16");
        }
        if (overlapCharacters < 0 || overlapCharacters >= maximumCharacters) {
            throw new IllegalArgumentException("overlapCharacters must be between 0 and maximumCharacters");
        }
        this.maximumCharacters = maximumCharacters;
        this.overlapCharacters = overlapCharacters;
    }

    public List<KnowledgeChunk> chunk(CanonicalDocument document) {
        if (document == null || document.documentId() == null || document.documentVersion() == null) {
            throw new IllegalArgumentException("document identity is required for chunking");
        }

        List<Section> sections = sections(document.text());
        List<KnowledgeChunk> chunks = new ArrayList<>();
        Set<String> seenContent = new LinkedHashSet<>();
        int ordinal = 0;
        for (Section section : sections) {
            for (String content : windows(section.text())) {
                if (!seenContent.add(content)) {
                    continue;
                }
                chunks.add(KnowledgeChunk.create(
                        document.documentId(),
                        document.documentVersion(),
                        ordinal++,
                        content,
                        section.heading(),
                        Map.of("sectionPath", section.heading())));
            }
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("document did not produce any chunks");
        }
        return List.copyOf(chunks);
    }

    private List<Section> sections(String text) {
        List<Section> sections = new ArrayList<>();
        String heading = "Document";
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\n", -1)) {
            if (isHeading(line)) {
                if (!body.isEmpty()) {
                    sections.add(new Section(heading, body.toString().trim()));
                    body.setLength(0);
                }
                heading = line.trim();
            } else {
                if (!body.isEmpty() || !line.isBlank()) {
                    if (!body.isEmpty()) {
                        body.append('\n');
                    }
                    body.append(line);
                }
            }
        }
        if (!body.isEmpty()) {
            sections.add(new Section(heading, body.toString().trim()));
        }
        return sections;
    }

    private List<String> windows(String sectionText) {
        if (sectionText.length() <= maximumCharacters) {
            return List.of(sectionText);
        }
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < sectionText.length()) {
            int end = Math.min(start + maximumCharacters, sectionText.length());
            result.add(sectionText.substring(start, end));
            if (end == sectionText.length()) {
                break;
            }
            start = end - overlapCharacters;
        }
        return result;
    }

    private static boolean isHeading(String line) {
        return line.matches("^#{1,6}\\s+\\S.*$");
    }

    private record Section(String heading, String body) {
        private String text() {
            return heading.equals("Document") ? body : heading + "\n" + body;
        }
    }
}
