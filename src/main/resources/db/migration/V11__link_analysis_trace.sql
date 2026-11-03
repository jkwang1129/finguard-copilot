ALTER TABLE analysis_run
    ADD COLUMN trace_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT ck_analysis_trace_id CHECK (trace_id IS NULL OR trace_id REGEXP '^[0-9a-f]{32}$');
