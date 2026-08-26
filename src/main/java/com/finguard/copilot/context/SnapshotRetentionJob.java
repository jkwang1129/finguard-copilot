package com.finguard.copilot.context;

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
public class SnapshotRetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotRetentionJob.class);
    private static final String FIND_EXPIRED = """
            SELECT analysis_id
            FROM analysis_input_snapshot
            WHERE facts_json IS NOT NULL
              AND deleted_at IS NULL
              AND expires_at <= ?
            ORDER BY expires_at, analysis_id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;
    private static final String DELETE_SNAPSHOT = """
            UPDATE analysis_input_snapshot s
            JOIN analysis_run r ON r.id = s.analysis_id
            SET s.facts_json = NULL,
                s.deleted_at = ?,
                r.fully_reproducible = FALSE,
                r.updated_at = ?
            WHERE s.analysis_id = ?
              AND s.facts_json IS NOT NULL
              AND s.deleted_at IS NULL
              AND s.expires_at <= ?
            """;

    private final JdbcTemplate jdbc;
    private final CopilotProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public SnapshotRetentionJob(JdbcTemplate jdbc, CopilotProperties properties) {
        this(jdbc, properties, Clock.systemUTC());
    }

    public SnapshotRetentionJob(JdbcTemplate jdbc, CopilotProperties properties, Clock clock) {
        this(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                properties, clock);
    }

    public SnapshotRetentionJob(JdbcTemplate jdbc, TransactionTemplate transactions,
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
        LOG.info("snapshot retention cleanup completed: deletedCount={}", total);
        return total;
    }

    private int cleanBatch(Instant now) {
        Instant normalizedNow = normalize(now);
        Instant expiryCutoff = normalizedNow;
        return requireCount(transactions.execute(status -> {
            List<byte[]> ids = jdbc.query(FIND_EXPIRED,
                    (resultSet, rowNum) -> resultSet.getBytes("analysis_id"),
                    Timestamp.from(expiryCutoff), properties.jobs().batchSize());
            int deleted = 0;
            for (byte[] id : ids) {
                if (jdbc.update(DELETE_SNAPSHOT,
                        Timestamp.from(normalizedNow), Timestamp.from(normalizedNow), id,
                        Timestamp.from(expiryCutoff)) > 0) {
                    deleted++;
                }
            }
            return deleted;
        }));
    }

    private static int requireCount(Integer count) {
        return count == null ? 0 : count;
    }

    private static Instant normalize(Instant instant) {
        return SnapshotCodec.toMicrosecondPrecision(instant);
    }

    private Instant now() {
        return normalize(Instant.now(clock));
    }
}
