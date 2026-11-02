CREATE TABLE model_versioned_artifact (
    artifact_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (artifact_kind, version_id),
    CONSTRAINT ck_model_artifact_json CHECK (JSON_VALID(content_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE model_run_manifest (
    analysis_id BINARY(16) NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (analysis_id),
    CONSTRAINT fk_model_manifest_run FOREIGN KEY (analysis_id) REFERENCES analysis_run(id) ON DELETE RESTRICT,
    CONSTRAINT ck_model_manifest_json CHECK (JSON_VALID(manifest_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
