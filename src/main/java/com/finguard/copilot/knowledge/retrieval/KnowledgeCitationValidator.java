package com.finguard.copilot.knowledge.retrieval;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.finguard.copilot.knowledge.domain.KnowledgeCitation;

public final class KnowledgeCitationValidator {

    public List<KnowledgeCitation> validate(List<KnowledgeEvidence> retrieved,
                                             List<KnowledgeCitation> outputCitations) {
        if (retrieved == null || outputCitations == null) {
            throw new CitationValidationException("citation collections must not be null");
        }
        Map<String, KnowledgeCitation> available = retrieved.stream()
                .map(KnowledgeEvidence::citation)
                .collect(Collectors.toMap(KnowledgeCitation::chunkId, Function.identity(), (first, second) -> first));
        if (outputCitations.stream().anyMatch(citation -> citation == null)) {
            throw new CitationValidationException("citation must not be null");
        }
        if (outputCitations.size() != new HashSet<>(outputCitations.stream()
                .map(KnowledgeCitation::chunkId).toList()).size()) {
            throw new CitationValidationException("duplicate citation chunk");
        }
        for (KnowledgeCitation citation : outputCitations) {
            KnowledgeCitation expected = available.get(citation.chunkId());
            if (expected == null || !expected.equals(citation)) {
                throw new CitationValidationException("citation is not an exact member of retrieved evidence");
            }
        }
        return List.copyOf(outputCitations);
    }
}
