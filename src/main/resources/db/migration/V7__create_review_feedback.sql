CREATE TABLE review_feedback (
    analysis_id BINARY(16) NOT NULL,
    reviewer_username VARCHAR(100) NOT NULL,
    verdict VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (analysis_id),
    INDEX idx_review_feedback_reviewer_created (reviewer_username, created_at),
    CONSTRAINT fk_review_feedback_run FOREIGN KEY (analysis_id)
        REFERENCES analysis_run (id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_feedback_verdict CHECK (verdict IN ('AGREE', 'DISAGREE', 'UNSURE'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
