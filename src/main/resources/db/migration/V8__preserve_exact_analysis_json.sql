-- MySQL binary JSON rounds arbitrary decimal tokens to floating point.
-- Preserve canonical JSON text and keep database JSON validity constraints.
-- Values already rounded in old JSON cannot be reconstructed; snapshot hashes still reject them.
ALTER TABLE analysis_input_snapshot
    MODIFY COLUMN facts_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT ck_snapshot_json_valid CHECK (facts_json IS NULL OR JSON_VALID(facts_json));

ALTER TABLE analysis_result
    MODIFY COLUMN result_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD CONSTRAINT ck_result_json_valid CHECK (JSON_VALID(result_json));
