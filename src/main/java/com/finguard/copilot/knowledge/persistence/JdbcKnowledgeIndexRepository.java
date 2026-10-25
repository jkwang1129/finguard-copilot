package com.finguard.copilot.knowledge.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexStatus;
import com.finguard.copilot.knowledge.domain.KnowledgeIndexVersion;
import com.finguard.copilot.knowledge.index.RagEvaluationResult;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcKnowledgeIndexRepository implements KnowledgeIndexRepository {

    private final JdbcTemplate jdbc;

    public JdbcKnowledgeIndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(KnowledgeIndexVersion version) {
        jdbc.update("""
                INSERT INTO knowledge_index_version
                    (index_version, status, created_at, published_at, replaced_version)
                VALUES (?, ?, ?, ?, ?)
                """, version.indexVersion(), version.status().name(), Timestamp.from(version.createdAt()),
                version.publishedAt() == null ? null : Timestamp.from(version.publishedAt()),
                version.replacedVersion());
    }

    @Override
    @Transactional
    public void save(KnowledgeIndexVersion version, List<KnowledgeChunk> chunks) {
        save(version);
        for (KnowledgeChunk chunk : chunks) {
            jdbc.update("""
                    INSERT INTO knowledge_index_membership (index_version, chunk_id)
                    VALUES (?, ?)
                    """, version.indexVersion(), chunk.chunkId());
        }
    }

    @Override
    public List<KnowledgeChunk> findChunks(String indexVersion) {
        return jdbc.query("""
                SELECT c.chunk_id, c.document_id, c.document_version, c.ordinal, c.content,
                       c.content_checksum, c.section_path, c.metadata_json
                FROM knowledge_index_membership m
                JOIN knowledge_chunk c ON c.chunk_id = m.chunk_id
                WHERE m.index_version = ?
                ORDER BY c.document_id, c.document_version, c.ordinal
                """, (rows, rowNumber) -> new KnowledgeChunk(
                rows.getString("chunk_id"), rows.getString("document_id"),
                rows.getString("document_version"), rows.getInt("ordinal"),
                rows.getString("content"), rows.getString("content_checksum"),
                rows.getString("section_path"), metadata(rows.getString("metadata_json"))), indexVersion);
    }

    @Override
    public Optional<KnowledgeIndexVersion> find(String indexVersion) {
        return queryOne("""
                SELECT index_version, status, created_at, published_at, replaced_version
                FROM knowledge_index_version WHERE index_version = ?
                """, indexVersion);
    }

    @Override
    public Optional<KnowledgeIndexVersion> findPublished() {
        return queryOne("""
                SELECT index_version, status, created_at, published_at, replaced_version
                FROM knowledge_index_version WHERE status = 'PUBLISHED'
                ORDER BY published_at DESC LIMIT 1
                """);
    }

    @Override
    public void markEvaluated(String indexVersion, RagEvaluationResult result) {
        int updated = jdbc.update("""
                UPDATE knowledge_index_version
                SET evaluation_recall_at_k = ?, evaluation_citation_precision = ?,
                    evaluation_no_answer_correctness = ?, evaluator_version = ?,
                    evaluated_at = ?
                WHERE index_version = ? AND status = 'EVALUATING'
                """, result.recallAtK(), result.citationPrecision(), result.noAnswerCorrectness(),
                result.evaluatorVersion(), Timestamp.from(java.time.Instant.now()), indexVersion);
        if (updated != 1) {
            throw new IllegalStateException("index is not evaluating: " + indexVersion);
        }
    }

    @Override
    public void markFailed(String indexVersion) {
        jdbc.update("""
                UPDATE knowledge_index_version SET status = 'FAILED'
                WHERE index_version = ? AND status <> 'PUBLISHED'
                """, indexVersion);
    }

    @Override
    @Transactional
    public void compareAndPublish(String candidateVersion, String expectedCurrentVersion) {
        Optional<String> current = selectPublishedForUpdate();
        if (!current.equals(Optional.ofNullable(expectedCurrentVersion))) {
            throw new IllegalStateException("published index changed; stale publication request");
        }
        KnowledgeIndexVersion candidate = selectForUpdate(candidateVersion)
                .orElseThrow(() -> new IllegalStateException("candidate index does not exist: " + candidateVersion));
        if (candidate.status() != KnowledgeIndexStatus.EVALUATING || !hasEvaluation(candidateVersion)) {
            throw new IllegalStateException("candidate has not been evaluated: " + candidateVersion);
        }
        if (expectedCurrentVersion != null) {
            jdbc.update("UPDATE knowledge_index_version SET status = 'RETIRED' WHERE index_version = ?",
                    expectedCurrentVersion);
        }
        jdbc.update("""
                UPDATE knowledge_index_version
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(6)
                WHERE index_version = ? AND status = 'EVALUATING'
                """, candidateVersion);
    }

    @Override
    @Transactional
    public void restoreAndPublish(String targetVersion, String expectedCurrentVersion) {
        Optional<String> current = selectPublishedForUpdate();
        if (!current.equals(Optional.ofNullable(expectedCurrentVersion))) {
            throw new IllegalStateException("published index changed; stale rollback request");
        }
        KnowledgeIndexVersion target = selectForUpdate(targetVersion)
                .orElseThrow(() -> new IllegalStateException("rollback target does not exist: " + targetVersion));
        if ((target.status() != KnowledgeIndexStatus.RETIRED && target.status() != KnowledgeIndexStatus.PUBLISHED)
                || !hasEvaluation(targetVersion) || membershipCount(targetVersion) == 0) {
            throw new IllegalStateException("rollback target is not retained and evaluated: " + targetVersion);
        }
        if (expectedCurrentVersion != null && !expectedCurrentVersion.equals(targetVersion)) {
            jdbc.update("UPDATE knowledge_index_version SET status = 'RETIRED' WHERE index_version = ?",
                    expectedCurrentVersion);
        }
        jdbc.update("""
                UPDATE knowledge_index_version
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(6)
                WHERE index_version = ?
                """, targetVersion);
    }

    private boolean hasEvaluation(String indexVersion) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_index_version
                WHERE index_version = ? AND evaluated_at IS NOT NULL
                """, Integer.class, indexVersion);
        return count != null && count == 1;
    }

    private int membershipCount(String indexVersion) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_index_membership WHERE index_version = ?", Integer.class, indexVersion);
        return count == null ? 0 : count;
    }

    private Optional<String> selectPublishedForUpdate() {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT index_version FROM knowledge_index_version WHERE status = 'PUBLISHED' FOR UPDATE",
                    String.class));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private Optional<KnowledgeIndexVersion> selectForUpdate(String indexVersion) {
        return queryOne("""
                SELECT index_version, status, created_at, published_at, replaced_version
                FROM knowledge_index_version WHERE index_version = ? FOR UPDATE
                """, indexVersion);
    }

    private Optional<KnowledgeIndexVersion> queryOne(String sql, Object... args) {
        List<KnowledgeIndexVersion> values = jdbc.query(sql, (rows, rowNumber) -> new KnowledgeIndexVersion(
                rows.getString("index_version"),
                KnowledgeIndexStatus.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("published_at") == null ? null : rows.getTimestamp("published_at").toInstant(),
                rows.getString("replaced_version")), args);
        return values.stream().findFirst();
    }

    private java.util.Map<String, String> metadata(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("knowledge metadata cannot be deserialized", exception);
        }
    }
}
