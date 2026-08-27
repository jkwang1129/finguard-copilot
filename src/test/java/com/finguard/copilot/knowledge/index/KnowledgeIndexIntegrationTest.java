package com.finguard.copilot.knowledge.index;

import java.time.Instant;
import java.util.List;

import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeDocument;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.domain.KnowledgeSourceType;
import com.finguard.copilot.knowledge.persistence.JdbcKnowledgeIndexRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD=",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.connection-timeout=5000"
})
@Testcontainers
class KnowledgeIndexIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withCommand("--log-bin-trust-function-creators=1");

    @Autowired
    private JdbcKnowledgeIndexRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanKnowledgeTables() {
        jdbc.update("DELETE FROM knowledge_citation");
        jdbc.update("DELETE FROM knowledge_index_membership");
        jdbc.update("DELETE FROM knowledge_index_version");
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document");
    }

    @Test
    void persistsEvaluationAndAtomicallySwitchesThePublishedPointer() {
        KnowledgeDocument document = KnowledgeDocument.register("policy-1", KnowledgeSourceType.POLICY,
                "Risk policy", "v1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                Instant.parse("2026-08-27T00:00:00Z"));
        KnowledgeChunk chunk = KnowledgeChunk.create(document.documentId(), document.sourceVersion(), 0,
                "Escalate high risk reviews.", "Risk", java.util.Map.of());
        insertDocument(document);
        insertChunk(chunk);

        repository.save(new KnowledgeIndexVersion("old", KnowledgeIndexStatus.PUBLISHED,
                Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:01Z"), null));
        repository.save(new KnowledgeIndexVersion("candidate", KnowledgeIndexStatus.EVALUATING,
                Instant.parse("2026-08-27T00:01:00Z"), null, "old"), List.of(chunk));
        RagEvaluationResult evaluation = new RagEvaluationResult(
                "candidate", 1.0, 1.0, 1.0, "rag-evaluator-v1", 1);
        repository.markEvaluated("candidate", evaluation);

        repository.compareAndPublish("candidate", "old");

        assertThat(repository.findPublished()).get()
                .extracting(KnowledgeIndexVersion::indexVersion).isEqualTo("candidate");
        assertThat(repository.find("old")).get()
                .extracting(KnowledgeIndexVersion::status).isEqualTo(KnowledgeIndexStatus.RETIRED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_membership WHERE index_version = 'candidate'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT evaluation_recall_at_k FROM knowledge_index_version WHERE index_version = 'candidate'",
                java.math.BigDecimal.class)).isEqualByComparingTo("1.000000000");
    }

    private void insertDocument(KnowledgeDocument document) {
        jdbc.update("""
                INSERT INTO knowledge_document
                    (document_id, source_version, source_type, title, canonical_checksum, sanitized, status, registered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, document.documentId(), document.sourceVersion(), document.sourceType().name(), document.title(),
                document.canonicalChecksum(), document.sanitized(), document.status(),
                java.sql.Timestamp.from(document.registeredAt()));
    }

    private void insertChunk(KnowledgeChunk chunk) {
        jdbc.update("""
                INSERT INTO knowledge_chunk
                    (chunk_id, document_id, document_version, ordinal, content, content_checksum, section_path, metadata_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, JSON_OBJECT())
                """, chunk.chunkId(), chunk.documentId(), chunk.documentVersion(), chunk.ordinal(), chunk.content(),
                chunk.contentChecksum(), chunk.sectionPath());
    }
}
