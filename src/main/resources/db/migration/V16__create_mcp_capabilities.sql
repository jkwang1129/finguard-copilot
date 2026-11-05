CREATE TABLE analysis_mcp_capability (
 capability_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 analysis_id BINARY(16) NOT NULL,
 reviewer_username VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 revision BIGINT NOT NULL,
 tool_scope_json LONGTEXT NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 revoked_at DATETIME(6) NULL,
 issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_mcp_agent FOREIGN KEY(analysis_id) REFERENCES agent_run_policy(analysis_id) ON DELETE CASCADE,
 CONSTRAINT ck_mcp_scope CHECK(JSON_VALID(tool_scope_json)),
 CONSTRAINT ck_mcp_revision CHECK(revision>=0),
 INDEX idx_mcp_run(analysis_id,expires_at)
) ENGINE=InnoDB;
