package com.finguard.copilot.usage;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** A committed, one-per-analysis call claim. Never delete a claim to retry an uncertain call. */
@Repository
public class ModelCallReservations {

    private final JdbcTemplate jdbc;

    public ModelCallReservations(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    public boolean reserve(UUID analysisId, Instant at) {
        if (analysisId == null || at == null) {
            throw new IllegalArgumentException("reservation inputs must not be null");
        }
        return jdbc.update("INSERT IGNORE INTO model_call_reservation (analysis_id, reserved_at) VALUES (?, ?)",
                JdbcAnalysisRunRepository.uuidBytes(analysisId), Timestamp.from(at)) == 1;
    }

    public Optional<Instant> reservedAt(UUID analysisId) {
        if (analysisId == null) {
            throw new IllegalArgumentException("analysisId must not be null");
        }
        return jdbc.query("SELECT reserved_at FROM model_call_reservation WHERE analysis_id = ?",
                resultSet -> resultSet.next()
                        ? Optional.of(resultSet.getTimestamp(1).toInstant()) : Optional.empty(),
                JdbcAnalysisRunRepository.uuidBytes(analysisId));
    }
}
