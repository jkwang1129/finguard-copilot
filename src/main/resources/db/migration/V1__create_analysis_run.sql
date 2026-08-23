CREATE TABLE analysis_run (
    id BINARY(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reviewer_username VARCHAR(100) NOT NULL,
    review_task_id BIGINT UNSIGNED NOT NULL,
    review_task_version BIGINT UNSIGNED NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_run_reviewer_idempotency
        UNIQUE (reviewer_username, idempotency_key),
    INDEX idx_analysis_run_review_task (review_task_id, review_task_version)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
