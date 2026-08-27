package com.finguard.copilot.knowledge.ingest;

import java.util.List;
import java.util.Map;

import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentChunkerTest {

    @Test
    void preservesHeadingsAndRemovesDuplicateChunksDeterministically() {
        CanonicalDocument document = new DocumentCleaner()
                .canonicalize("# Risk\n\nAmount review rule.\n\nAmount review rule.\n\n# Operations\n\nEscalate to reviewer.")
                .withIdentity("doc-1", "v1");
        DocumentChunker chunker = new DocumentChunker(80, 8);

        List<KnowledgeChunk> first = chunker.chunk(document);
        List<KnowledgeChunk> second = chunker.chunk(document);

        assertThat(first).containsExactlyElementsOf(second);
        assertThat(first).extracting(KnowledgeChunk::sectionPath)
                .containsExactly("# Risk", "# Operations");
        assertThat(first.get(0).content()).contains("Amount review rule.");
        assertThat(first).hasSize(2);
    }

    @Test
    void appliesStableCharacterOverlapWhenSectionExceedsLimit() {
        CanonicalDocument document = new CanonicalDocument(
                "doc-1", "v1", "# Risk\nalpha beta gamma delta epsilon zeta eta theta", "checksum");

        List<KnowledgeChunk> chunks = new DocumentChunker(28, 6).chunk(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (KnowledgeChunk chunk : chunks) {
            assertThat(chunk.content().length()).isLessThanOrEqualTo(28);
        }
        assertThat(chunks.get(0).content().substring(chunks.get(0).content().length() - 6))
                .isEqualTo(chunks.get(1).content().substring(0, 6));
    }

    @Test
    void rejectsInvalidChunkConfiguration() {
        assertThatThrownBy(() -> new DocumentChunker(10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
