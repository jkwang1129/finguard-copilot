package com.finguard.copilot.knowledge.retrieval;

import java.util.List;

import com.finguard.copilot.knowledge.domain.KnowledgeCitation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeCitationValidatorTest {

    @Test
    void rejectsFabricatedAndDuplicateOutputCitations() {
        KnowledgeCitation retrieved = new KnowledgeCitation("doc", "v1", "doc:v1:0", "Risk",
                "a".repeat(64), 0.9, "published");
        KnowledgeEvidence evidence = new KnowledgeEvidence(retrieved, "risk policy");
        KnowledgeCitation fabricated = new KnowledgeCitation("doc", "v1", "doc:v1:1", "Risk",
                "b".repeat(64), 0.9, "published");
        KnowledgeCitation alteredScore = new KnowledgeCitation("doc", "v1", "doc:v1:0", "Risk",
                "a".repeat(64), 0.8, "published");
        KnowledgeCitationValidator validator = new KnowledgeCitationValidator();

        assertThatThrownBy(() -> validator.validate(List.of(evidence), List.of(fabricated)))
                .isInstanceOf(CitationValidationException.class);
        assertThatThrownBy(() -> validator.validate(List.of(evidence), List.of(retrieved, retrieved)))
                .isInstanceOf(CitationValidationException.class);
        assertThatThrownBy(() -> validator.validate(List.of(evidence), List.of(alteredScore)))
                .isInstanceOf(CitationValidationException.class);
    }
}
