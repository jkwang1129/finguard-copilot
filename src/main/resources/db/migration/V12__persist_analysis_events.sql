CREATE TABLE analysis_event_cursor (
    analysis_id BINARY(16) NOT NULL PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_event_cursor_run FOREIGN KEY(analysis_id) REFERENCES analysis_run(id) ON DELETE CASCADE,
    CONSTRAINT ck_event_cursor_positive CHECK(last_sequence >= 0)
) ENGINE=InnoDB;

CREATE TABLE analysis_event (
    analysis_id BINARY(16) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY(analysis_id,sequence_number),
    CONSTRAINT fk_event_run FOREIGN KEY(analysis_id) REFERENCES analysis_run(id) ON DELETE CASCADE,
    CONSTRAINT ck_event_sequence CHECK(sequence_number > 0),
    CONSTRAINT ck_event_type CHECK(event_type IN ('SNAPSHOT','STATE_CHANGED')),
    CONSTRAINT ck_event_status CHECK(analysis_status IN ('CONTEXT_READY','QUEUED','RETRIEVAL_READY','GENERATING','VALIDATING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_event_failure CHECK((analysis_status='FAILED' AND failure_code IS NOT NULL) OR (analysis_status<>'FAILED' AND failure_code IS NULL))
) ENGINE=InnoDB;

-- Existing runs get one current-state snapshot, not an invented history.
INSERT INTO analysis_event_cursor(analysis_id,last_sequence) SELECT id,1 FROM analysis_run;
INSERT INTO analysis_event(analysis_id,sequence_number,event_type,analysis_status,failure_code,occurred_at)
SELECT id,1,'SNAPSHOT',status,
 CASE WHEN status<>'FAILED' THEN NULL
      WHEN failure_code IN ('UNAUTHENTICATED','FORBIDDEN','VALIDATION_FAILED','INVALID_REQUEST','RESOURCE_NOT_FOUND','CONFLICT',
       'CONTEXT_BUDGET_EXCEEDED','OUTPUT_SCHEMA_INVALID','FACT_VALIDATION_FAILED','KNOWLEDGE_UNAVAILABLE','MODEL_UNAVAILABLE',
       'MODEL_TIMEOUT','MODEL_RATE_LIMITED','MODEL_OUTPUT_INVALID','MODEL_CONFIGURATION_INVALID','MODEL_BUDGET_EXCEEDED',
       'MODEL_CAPACITY_UNAVAILABLE','BROKER_UNAVAILABLE','ANALYSIS_RETRY_EXHAUSTED','ANALYSIS_WORKER_FAILED',
       'SERVICE_UNAVAILABLE','INTERNAL_ERROR','MODEL_CALL_OUTCOME_UNKNOWN','MODEL_PREPARATION_UNAVAILABLE') THEN failure_code
      ELSE 'INTERNAL_ERROR' END,
 updated_at FROM analysis_run;
