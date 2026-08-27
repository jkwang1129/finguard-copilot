CREATE TABLE knowledge_document (
    document_id VARCHAR(128) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    canonical_checksum CHAR(64) NOT NULL,
    sanitized BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    registered_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (document_id, source_version),
    CONSTRAINT uk_knowledge_document_checksum
        UNIQUE (document_id, source_version, canonical_checksum),
    CONSTRAINT ck_knowledge_document_source_type CHECK (
        source_type IN ('POLICY', 'RULE_EXPLANATION', 'MANUAL', 'RUNBOOK', 'SANITIZED_CASE')
    ),
    CONSTRAINT ck_knowledge_document_sanitized CHECK (sanitized IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_chunk (
    chunk_id VARCHAR(255) NOT NULL,
    document_id VARCHAR(128) NOT NULL,
    document_version VARCHAR(128) NOT NULL,
    ordinal INT NOT NULL,
    content LONGTEXT NOT NULL,
    content_checksum CHAR(64) NOT NULL,
    section_path VARCHAR(512) NOT NULL,
    metadata_json JSON NOT NULL,
    PRIMARY KEY (chunk_id),
    CONSTRAINT uk_knowledge_chunk_ordinal
        UNIQUE (document_id, document_version, ordinal),
    CONSTRAINT fk_knowledge_chunk_document
        FOREIGN KEY (document_id, document_version)
        REFERENCES knowledge_document (document_id, source_version)
        ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_chunk_ordinal CHECK (ordinal >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_index_version (
    index_version VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    replaced_version VARCHAR(128) NULL,
    PRIMARY KEY (index_version),
    CONSTRAINT ck_knowledge_index_status CHECK (
        status IN ('DRAFT', 'EVALUATING', 'PUBLISHED', 'RETIRED', 'FAILED')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_index_membership (
    index_version VARCHAR(128) NOT NULL,
    chunk_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (index_version, chunk_id),
    CONSTRAINT fk_knowledge_membership_index
        FOREIGN KEY (index_version) REFERENCES knowledge_index_version (index_version)
        ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_membership_chunk
        FOREIGN KEY (chunk_id) REFERENCES knowledge_chunk (chunk_id)
        ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_citation (
    citation_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    analysis_id BINARY(16) NOT NULL,
    index_version VARCHAR(128) NOT NULL,
    document_id VARCHAR(128) NOT NULL,
    document_version VARCHAR(128) NOT NULL,
    chunk_id VARCHAR(255) NOT NULL,
    section_path VARCHAR(512) NOT NULL,
    content_checksum CHAR(64) NOT NULL,
    score DECIMAL(10, 9) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (citation_id),
    INDEX idx_knowledge_citation_analysis (analysis_id),
    CONSTRAINT fk_knowledge_citation_run
        FOREIGN KEY (analysis_id) REFERENCES analysis_run (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_citation_index
        FOREIGN KEY (index_version) REFERENCES knowledge_index_version (index_version)
        ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_citation_chunk
        FOREIGN KEY (chunk_id) REFERENCES knowledge_chunk (chunk_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_citation_document
        FOREIGN KEY (document_id, document_version)
        REFERENCES knowledge_document (document_id, source_version)
        ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_citation_score CHECK (score BETWEEN -1 AND 1)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
