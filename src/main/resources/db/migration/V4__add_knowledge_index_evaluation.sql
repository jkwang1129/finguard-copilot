ALTER TABLE knowledge_index_version
    ADD COLUMN evaluation_recall_at_k DECIMAL(10, 9) NULL,
    ADD COLUMN evaluation_citation_precision DECIMAL(10, 9) NULL,
    ADD COLUMN evaluation_no_answer_correctness DECIMAL(10, 9) NULL,
    ADD COLUMN evaluator_version VARCHAR(128) NULL,
    ADD COLUMN evaluated_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_knowledge_index_evaluation_scores CHECK (
        (evaluation_recall_at_k IS NULL AND evaluation_citation_precision IS NULL
            AND evaluation_no_answer_correctness IS NULL AND evaluator_version IS NULL AND evaluated_at IS NULL)
        OR (evaluation_recall_at_k BETWEEN 0 AND 1
            AND evaluation_citation_precision BETWEEN 0 AND 1
            AND evaluation_no_answer_correctness BETWEEN 0 AND 1
            AND evaluator_version IS NOT NULL AND evaluated_at IS NOT NULL)
    );
