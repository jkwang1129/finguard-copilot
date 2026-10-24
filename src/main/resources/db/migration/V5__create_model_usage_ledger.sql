CREATE TABLE model_usage_ledger (
    analysis_id BINARY(16) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_configuration_version VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(128) NOT NULL,
    input_tokens INT UNSIGNED NOT NULL,
    output_tokens INT UNSIGNED NOT NULL,
    latency_ms BIGINT UNSIGNED NOT NULL,
    estimated_cost DECIMAL(20, 8) NOT NULL,
    currency CHAR(3) NOT NULL,
    price_table_version VARCHAR(128) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (analysis_id),
    CONSTRAINT fk_model_usage_analysis
        FOREIGN KEY (analysis_id) REFERENCES analysis_run (id),
    CONSTRAINT uk_model_usage_analysis UNIQUE (analysis_id),
    CONSTRAINT ck_model_usage_cost CHECK (estimated_cost >= 0),
    CONSTRAINT ck_model_usage_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'TIMEOUT', 'RATE_LIMITED')),
    INDEX idx_model_usage_provider_created (provider, created_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
