package com.finguard.copilot.knowledge.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.knowledge.domain.KnowledgeChunk;
import com.finguard.copilot.knowledge.domain.KnowledgeDocument;
import com.finguard.copilot.knowledge.domain.KnowledgeSourceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcKnowledgeDocumentRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<KnowledgeDocument> findLatestByDocumentId(String documentId) {
        List<KnowledgeDocument> documents = jdbc.query("""
                SELECT document_id, source_type, title, canonical_checksum,
                       sanitized, source_version, registered_at, status
                FROM knowledge_document
                WHERE document_id = ?
                ORDER BY registered_at DESC, source_version DESC
                LIMIT 1
                """, (rows, rowNumber) -> new KnowledgeDocument(
                rows.getString("document_id"),
                KnowledgeSourceType.valueOf(rows.getString("source_type")),
                rows.getString("title"),
                rows.getString("canonical_checksum"),
                rows.getBoolean("sanitized"),
                rows.getString("source_version"),
                rows.getTimestamp("registered_at").toInstant(),
                rows.getString("status")), documentId);
        return documents.stream().findFirst();
    }

    @Override
    public List<KnowledgeChunk> findChunks(String documentId, String documentVersion) {
        return jdbc.query("""
                SELECT chunk_id, document_id, document_version, ordinal, content,
                       content_checksum, section_path, metadata_json
                FROM knowledge_chunk
                WHERE document_id = ? AND document_version = ?
                ORDER BY ordinal
                """, (rows, rowNumber) -> new KnowledgeChunk(
                rows.getString("chunk_id"), rows.getString("document_id"),
                rows.getString("document_version"), rows.getInt("ordinal"),
                rows.getString("content"), rows.getString("content_checksum"),
                rows.getString("section_path"), metadata(rows.getString("metadata_json"))),
                documentId, documentVersion);
    }

    @Override
    public Optional<KnowledgeDocument> findByCanonicalChecksum(
            String documentId, String sourceVersion, String canonicalChecksum) {
        List<KnowledgeDocument> documents = jdbc.query("""
                SELECT document_id, source_type, title, canonical_checksum,
                       sanitized, source_version, registered_at, status
                FROM knowledge_document
                WHERE document_id = ?
                  AND source_version = ?
                  AND canonical_checksum = ?
                """, (rows, rowNumber) -> new KnowledgeDocument(
                rows.getString("document_id"),
                KnowledgeSourceType.valueOf(rows.getString("source_type")),
                rows.getString("title"),
                rows.getString("canonical_checksum"),
                rows.getBoolean("sanitized"),
                rows.getString("source_version"),
                rows.getTimestamp("registered_at").toInstant(),
                rows.getString("status")),
                documentId, sourceVersion, canonicalChecksum);
        return documents.stream().findFirst();
    }

    @Override
    @Transactional
    public void save(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        jdbc.update("""
                INSERT INTO knowledge_document (
                    document_id, source_version, source_type, title, canonical_checksum,
                    sanitized, status, registered_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                document.documentId(), document.sourceVersion(), document.sourceType().name(),
                document.title(), document.canonicalChecksum(), document.sanitized(),
                document.status(), Timestamp.from(document.registeredAt()));
        for (KnowledgeChunk chunk : chunks) {
            jdbc.update("""
                    INSERT INTO knowledge_chunk (
                        chunk_id, document_id, document_version, ordinal, content,
                        content_checksum, section_path, metadata_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    chunk.chunkId(), chunk.documentId(), chunk.documentVersion(), chunk.ordinal(),
                    chunk.content(), chunk.contentChecksum(), chunk.sectionPath(), metadataJson(chunk.metadata()));
        }
    }

    private String metadataJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("knowledge metadata cannot be serialized", exception);
        }
    }

    private Map<String, String> metadata(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("knowledge metadata cannot be deserialized", exception);
        }
    }
}
