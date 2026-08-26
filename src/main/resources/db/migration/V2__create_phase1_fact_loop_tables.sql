ALTER TABLE analysis_run
    DROP INDEX uk_analysis_run_reviewer_idempotency,
    ADD COLUMN failure_code VARCHAR(64) NULL AFTER updated_at,
    ADD COLUMN fully_reproducible BOOLEAN NOT NULL DEFAULT TRUE AFTER failure_code,
    ADD CONSTRAINT ck_analysis_run_status CHECK (
        status IN (
            'CONTEXT_READY',
            'QUEUED',
            'RETRIEVAL_READY',
            'GENERATING',
            'VALIDATING',
            'SUCCEEDED',
            'FAILED'
        )
    ),
    ADD CONSTRAINT ck_analysis_run_failure CHECK (
        (status = 'FAILED' AND failure_code IS NOT NULL)
        OR (status <> 'FAILED' AND failure_code IS NULL)
    ),
    ADD CONSTRAINT ck_analysis_run_reproducible CHECK (fully_reproducible IN (0, 1));

CREATE TABLE analysis_idempotency_key (
    analysis_id BINARY(16) NOT NULL,
    reviewer_username VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash BINARY(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (analysis_id),
    CONSTRAINT uk_analysis_idempotency_reviewer_key
        UNIQUE (reviewer_username, idempotency_key),
    INDEX idx_analysis_idempotency_expires (expires_at),
    CONSTRAINT fk_analysis_idempotency_run
        FOREIGN KEY (analysis_id) REFERENCES analysis_run (id) ON DELETE RESTRICT,
    CONSTRAINT ck_analysis_idempotency_expiry CHECK (expires_at > created_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE analysis_input_snapshot (
    analysis_id BINARY(16) NOT NULL,
    schema_version INT UNSIGNED NOT NULL,
    facts_json JSON NULL,
    facts_sha256 BINARY(32) NOT NULL,
    captured_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (analysis_id),
    INDEX idx_analysis_snapshot_expiry (expires_at, deleted_at),
    CONSTRAINT fk_analysis_snapshot_run
        FOREIGN KEY (analysis_id) REFERENCES analysis_run (id) ON DELETE RESTRICT,
    CONSTRAINT ck_analysis_snapshot_schema CHECK (schema_version = 1),
    CONSTRAINT ck_analysis_snapshot_expiry CHECK (expires_at > captured_at),
    CONSTRAINT ck_analysis_snapshot_lifecycle CHECK (
        (facts_json IS NOT NULL AND deleted_at IS NULL)
        OR (facts_json IS NULL AND deleted_at IS NOT NULL)
    ),
    CONSTRAINT ck_analysis_snapshot_deleted_after_expiry CHECK (
        deleted_at IS NULL OR deleted_at >= expires_at
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE analysis_result (
    analysis_id BINARY(16) NOT NULL,
    schema_version INT UNSIGNED NOT NULL,
    recommendation VARCHAR(32) NOT NULL,
    result_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (analysis_id),
    CONSTRAINT fk_analysis_result_run
        FOREIGN KEY (analysis_id) REFERENCES analysis_run (id) ON DELETE RESTRICT,
    CONSTRAINT ck_analysis_result_schema CHECK (schema_version = 1),
    CONSTRAINT ck_analysis_result_recommendation CHECK (
        recommendation IN ('CONFIRMED', 'IGNORED', 'INSUFFICIENT_EVIDENCE')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
    id BINARY(16) NOT NULL,
    analysis_id BINARY(16) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INT UNSIGNED NOT NULL,
    payload_json JSON NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_outbox_event_due (published_at, next_attempt_at, created_at),
    CONSTRAINT fk_outbox_event_run
        FOREIGN KEY (analysis_id) REFERENCES analysis_run (id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbox_event_type CHECK (event_type = 'ANALYSIS_REQUESTED'),
    CONSTRAINT ck_outbox_event_schema CHECK (schema_version = 1),
    CONSTRAINT ck_outbox_last_error_code CHECK (
        last_error_code IS NULL
        OR CHAR_LENGTH(last_error_code) BETWEEN 1 AND 64
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE analysis_message_receipt (
    event_id BINARY(16) NOT NULL,
    analysis_id BINARY(16) NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    CONSTRAINT fk_analysis_receipt_run
        FOREIGN KEY (analysis_id) REFERENCES analysis_run (id) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
