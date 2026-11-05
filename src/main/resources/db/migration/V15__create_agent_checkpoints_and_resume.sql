ALTER TABLE agent_run_policy ADD COLUMN runtime_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_model_call ADD COLUMN provider_response_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE analysis_event ADD COLUMN agent_revision BIGINT NULL,
 ADD COLUMN agent_node VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN agent_tool_names VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE analysis_run DROP CHECK ck_analysis_run_status,
 ADD CONSTRAINT ck_analysis_run_status CHECK(status IN
 ('CONTEXT_READY','QUEUED','RETRIEVAL_READY','GENERATING','VALIDATING','SUCCEEDED','FAILED','PLANNING','TOOL_RUNNING','WAITING_FOR_HUMAN'));
ALTER TABLE analysis_event DROP CHECK ck_event_status,
 ADD CONSTRAINT ck_event_status CHECK(analysis_status IN
 ('CONTEXT_READY','QUEUED','RETRIEVAL_READY','GENERATING','VALIDATING','SUCCEEDED','FAILED','PLANNING','TOOL_RUNNING','WAITING_FOR_HUMAN'));

CREATE TABLE analysis_checkpoint (
 analysis_id BINARY(16) NOT NULL PRIMARY KEY,
 schema_version INT NOT NULL,
 workflow_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 next_node VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 memory_json LONGTEXT NULL,
 memory_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 snapshot_sha256 BINARY(32) NOT NULL,
 index_version VARCHAR(128) NOT NULL,
 owner_token VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME(6) NULL,
 waiting BOOLEAN NOT NULL DEFAULT FALSE,
 human_approved BOOLEAN NOT NULL DEFAULT FALSE,
 wait_expires_at DATETIME(6) NULL,
 model_calls INT NOT NULL DEFAULT 0,
 tool_calls INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_checkpoint_agent FOREIGN KEY(analysis_id) REFERENCES agent_run_policy(analysis_id) ON DELETE CASCADE,
 CONSTRAINT ck_checkpoint_revision CHECK(revision>=0),
 CONSTRAINT ck_checkpoint_memory CHECK(memory_json IS NULL OR JSON_VALID(memory_json)),
 CONSTRAINT ck_checkpoint_calls CHECK(model_calls BETWEEN 0 AND 2 AND tool_calls BETWEEN 0 AND 4),
 CONSTRAINT ck_checkpoint_wait CHECK((waiting=TRUE AND wait_expires_at IS NOT NULL AND owner_token IS NULL AND lease_until IS NULL)
     OR (waiting=FALSE AND wait_expires_at IS NULL)),
 INDEX idx_checkpoint_recovery(waiting,lease_until),
 INDEX idx_checkpoint_wait(waiting,wait_expires_at)
) ENGINE=InnoDB;

CREATE TABLE analysis_resume_request (
 analysis_id BINARY(16) NOT NULL,
 reviewer_username VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 response_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(analysis_id,reviewer_username,idempotency_key),
 CONSTRAINT fk_resume_agent FOREIGN KEY(analysis_id) REFERENCES agent_run_policy(analysis_id) ON DELETE CASCADE,
 CONSTRAINT ck_resume_action CHECK(action IN ('CONTINUE','CANCEL'))
) ENGINE=InnoDB;
