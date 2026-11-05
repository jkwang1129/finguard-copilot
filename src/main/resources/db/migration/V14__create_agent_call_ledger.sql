CREATE TABLE agent_model_policy (
    analysis_id BINARY(16) NOT NULL PRIMARY KEY,
    policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_json LONGTEXT NOT NULL,
    CONSTRAINT fk_agent_model_policy FOREIGN KEY(analysis_id) REFERENCES agent_run_policy(analysis_id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_model_policy_json CHECK(JSON_VALID(policy_json))
) ENGINE=InnoDB;

CREATE TABLE agent_model_call (
    analysis_id BINARY(16) NOT NULL,
    call_ordinal INT NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_json LONGTEXT NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_cny DECIMAL(20,8) NOT NULL,
    settled_cny DECIMAL(20,8) NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    latency_ms BIGINT NULL,
    outcome_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    outcome_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(analysis_id,call_ordinal),
    CONSTRAINT fk_agent_model_run FOREIGN KEY(analysis_id) REFERENCES agent_model_policy(analysis_id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_model_ordinal CHECK(call_ordinal BETWEEN 1 AND 2),
    CONSTRAINT ck_agent_model_manifest CHECK(JSON_VALID(manifest_json)),
    CONSTRAINT ck_agent_model_status CHECK(status IN ('RESERVED','SETTLED','UNKNOWN_HELD','RELEASED_NOT_SENT')),
    CONSTRAINT ck_agent_model_money CHECK(reserved_cny>=0 AND (settled_cny IS NULL OR settled_cny>=0)),
    CONSTRAINT ck_agent_model_outcome CHECK(outcome_type IS NULL OR outcome_type IN ('SUCCESS','FAILURE','UNKNOWN','NOT_SENT'))
) ENGINE=InnoDB;

CREATE TABLE agent_model_budget_allocation (
    analysis_id BINARY(16) NOT NULL,
    call_ordinal INT NOT NULL,
    scope_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(analysis_id,call_ordinal,scope_key),
    CONSTRAINT fk_agent_allocation_call FOREIGN KEY(analysis_id,call_ordinal) REFERENCES agent_model_call(analysis_id,call_ordinal) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_allocation_account FOREIGN KEY(scope_key) REFERENCES model_budget_account(scope_key) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE agent_model_budget_event (
    analysis_id BINARY(16) NOT NULL,
    call_ordinal INT NOT NULL,
    event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    details_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(analysis_id,call_ordinal,event_type),
    CONSTRAINT fk_agent_budget_event FOREIGN KEY(analysis_id,call_ordinal) REFERENCES agent_model_call(analysis_id,call_ordinal) ON DELETE RESTRICT
) ENGINE=InnoDB;
