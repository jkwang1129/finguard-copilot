package com.finguard.copilot.analysis.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.finguard.copilot.config.CopilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(prefix = "finguard.copilot.jobs", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class IdempotencyCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyCleanupJob.class);
    private static final String FIND_EXPIRED = """
            SELECT analysis_id, reviewer_username, idempotency_key, expires_at
            FROM analysis_idempotency_key
            WHERE expires_at <= ?
            ORDER BY expires_at, analysis_id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;
    private static final String DELETE_EXPIRED = """
            DELETE FROM analysis_idempotency_key
            WHERE analysis_id = ?
              AND reviewer_username = ?
              AND idempotency_key = ?
              AND expires_at = ?
              AND expires_at <= ?
            """;

    private final JdbcTemplate jdbc;
    private final CopilotProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public IdempotencyCleanupJob(JdbcTemplate jdbc, CopilotProperties properties) {
        this(jdbc, properties, Clock.systemUTC());
    }

    public IdempotencyCleanupJob(JdbcTemplate jdbc, CopilotProperties properties, Clock clock) {
        this(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                properties, clock);
    }

    public IdempotencyCleanupJob(JdbcTemplate jdbc, TransactionTemplate transactions,
                                 CopilotProperties properties, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Scheduled(fixedDelayString = "${finguard.copilot.jobs.fixed-delay:1h}")
    public void scheduledCleanup() {
        if (properties.jobs().enabled()) {
            runOnce();
        }
    }

    public int runOnce() {
        int total = 0;
        int batch;
        do {
            batch = cleanBatch(now());
            total += batch;
        } while (batch == properties.jobs().batchSize());
        LOG.info("idempotency cleanup completed: deletedCount={}", total);
        return total;
    }

    private int cleanBatch(Instant now) {
        Instant normalizedNow = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return requireCount(transactions.execute(status -> {
            List<ExpiredReservation> reservations = jdbc.query(FIND_EXPIRED,
                    (resultSet, rowNum) -> new ExpiredReservation(
                            resultSet.getBytes("analysis_id"),
                            resultSet.getString("reviewer_username"),
                            resultSet.getString("idempotency_key"),
                            resultSet.getTimestamp("expires_at")),
                    Timestamp.from(normalizedNow), properties.jobs().batchSize());
            int deleted = 0;
            for (ExpiredReservation reservation : reservations) {
                deleted += jdbc.update(DELETE_EXPIRED,
                        reservation.analysisId(), reservation.reviewerUsername(),
                        reservation.idempotencyKey(), reservation.expiresAt(),
                        Timestamp.from(normalizedNow));
            }
            return deleted;
        }));
    }

    private static int requireCount(Integer count) {
        return count == null ? 0 : count;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private record ExpiredReservation(byte[] analysisId, String reviewerUsername,
                                      String idempotencyKey, Timestamp expiresAt) {
    }
}
