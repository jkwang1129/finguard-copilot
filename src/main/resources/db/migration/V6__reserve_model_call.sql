CREATE TABLE model_call_reservation (
    analysis_id BINARY(16) NOT NULL PRIMARY KEY,
    reserved_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_model_call_reservation_analysis
        FOREIGN KEY (analysis_id) REFERENCES analysis_run (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
