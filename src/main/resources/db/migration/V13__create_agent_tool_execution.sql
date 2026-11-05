CREATE TABLE agent_run_policy (
    analysis_id BINARY(16) NOT NULL PRIMARY KEY,
    workflow_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_sha256 BINARY(32) NOT NULL,
    index_version VARCHAR(128) NULL,
    max_tool_calls INT NOT NULL DEFAULT 4,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_agent_policy_run FOREIGN KEY(analysis_id) REFERENCES analysis_run(id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_tool_limit CHECK(max_tool_calls=4),
    CONSTRAINT ck_agent_workflow CHECK(workflow_version='agent-v1')
) ENGINE=InnoDB;

CREATE TABLE analysis_tool_execution (
    analysis_id BINARY(16) NOT NULL,
    slot INT NOT NULL,
    step_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tool_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    arguments_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_json LONGTEXT NULL,
    result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    PRIMARY KEY(analysis_id,slot),
    UNIQUE KEY uk_agent_tool_step(analysis_id,step_key),
    CONSTRAINT fk_agent_tool_policy FOREIGN KEY(analysis_id) REFERENCES agent_run_policy(analysis_id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_tool_slot CHECK(slot BETWEEN 1 AND 4),
    CONSTRAINT ck_agent_tool_status CHECK(status IN ('CLAIMED','SUCCEEDED','REJECTED','UNKNOWN')),
    CONSTRAINT ck_agent_tool_json CHECK(result_json IS NULL OR JSON_VALID(result_json))
) ENGINE=InnoDB;
