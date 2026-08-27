package com.finguard.copilot.knowledge.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDomainTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-08-27T06:00:00Z");

    @Test
    void rejectsDynamicCoreFacts() {
        assertThatThrownBy(() -> KnowledgeDocument.register(
                "doc-1", KnowledgeSourceType.DYNAMIC_CORE_FACT, "task", "v1", "abc", REGISTERED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceType");
    }

    @Test
    void acceptsOnlyCuratedKnowledgeSources() {
        for (KnowledgeSourceType sourceType : KnowledgeSourceType.values()) {
            if (sourceType == KnowledgeSourceType.DYNAMIC_CORE_FACT) {
                continue;
            }

            KnowledgeDocument document = KnowledgeDocument.register(
                    "doc-" + sourceType.name(), sourceType, "title", "v1", "checksum", REGISTERED_AT);

            assertThat(document.sourceType()).isEqualTo(sourceType);
            assertThat(document.sanitized()).isTrue();
            assertThat(document.status()).isEqualTo("ACTIVE");
        }
    }

    @Test
    void chunkIdentityIncludesDocumentVersionAndOrdinal() {
        KnowledgeChunk chunk = KnowledgeChunk.create("doc-1", "v1", 0, "policy text", " / Risk / ", Map.of());

        assertThat(chunk.chunkId()).isEqualTo("doc-1:v1:0");
        assertThat(chunk.sectionPath()).isEqualTo("Risk");
        assertThat(chunk.contentChecksum()).hasSize(64);
    }

    @Test
    void chunkMetadataIsDefensivelyCopied() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("language", "zh-CN");

        KnowledgeChunk chunk = KnowledgeChunk.create("doc-1", "v1", 1, "text", "Risk", metadata);
        metadata.put("leaked", "value");

        assertThat(chunk.metadata()).containsExactly(Map.entry("language", "zh-CN"));
        assertThatThrownBy(() -> chunk.metadata().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void indexVersionAndCitationRequireStableIdentifiers() {
        KnowledgeIndexVersion index = new KnowledgeIndexVersion(
                "idx-v1", KnowledgeIndexStatus.DRAFT, REGISTERED_AT, null, null);
        KnowledgeCitation citation = new KnowledgeCitation(
                "doc-1", "v1", "doc-1:v1:0", "Risk", "checksum", 0.91, "idx-v1");

        assertThat(index.status()).isEqualTo(KnowledgeIndexStatus.DRAFT);
        assertThat(citation.score()).isEqualTo(0.91);
        assertThatThrownBy(() -> new KnowledgeCitation(
                "doc-1", "v1", "chunk", "Risk", "checksum", Double.NaN, "idx-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score");
    }
}
