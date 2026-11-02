CREATE TABLE model_budget_account (
    scope_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    limit_cny DECIMAL(20,8) NOT NULL,
    held_cny DECIMAL(20,8) NOT NULL DEFAULT 0,
    settled_cny DECIMAL(20,8) NOT NULL DEFAULT 0,
    PRIMARY KEY(scope_key),
    CONSTRAINT ck_budget_account_amounts CHECK(limit_cny > 0 AND held_cny >= 0 AND settled_cny >= 0 AND currency='CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE model_budget_reservation (
    analysis_id BINARY(16) NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_cny DECIMAL(20,8) NOT NULL,
    settled_cny DECIMAL(20,8) NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(analysis_id),
    CONSTRAINT fk_budget_manifest FOREIGN KEY(analysis_id) REFERENCES model_run_manifest(analysis_id) ON DELETE RESTRICT,
    CONSTRAINT ck_budget_status CHECK(status IN ('RESERVED','SETTLED','UNKNOWN_HELD','RELEASED_NOT_SENT')),
    CONSTRAINT ck_budget_reservation_amounts CHECK(reserved_cny >= 0 AND (settled_cny IS NULL OR settled_cny >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE model_budget_allocation (
    analysis_id BINARY(16) NOT NULL,
    scope_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(analysis_id,scope_key),
    CONSTRAINT fk_budget_allocation_reservation FOREIGN KEY(analysis_id) REFERENCES model_budget_reservation(analysis_id) ON DELETE RESTRICT,
    CONSTRAINT fk_budget_allocation_account FOREIGN KEY(scope_key) REFERENCES model_budget_account(scope_key) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE model_budget_event (
    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_id BINARY(16) NOT NULL,
    event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    details_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(event_id),
    INDEX idx_budget_event_analysis(analysis_id,created_at),
    CONSTRAINT fk_budget_event_reservation FOREIGN KEY(analysis_id) REFERENCES model_budget_reservation(analysis_id) ON DELETE RESTRICT,
    CONSTRAINT ck_budget_event_json CHECK(JSON_VALID(details_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
