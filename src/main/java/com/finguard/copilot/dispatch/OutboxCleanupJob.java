package com.finguard.copilot.dispatch;

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
public class OutboxCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxCleanupJob.class);
    private static final String FIND_ELIGIBLE = """
            SELECT id
            FROM outbox_event
            WHERE published_at IS NOT NULL
              AND published_at < ?
            ORDER BY published_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;
    private static final String DELETE_ELIGIBLE = """
            DELETE FROM outbox_event
            WHERE id = ?
              AND published_at IS NOT NULL
              AND published_at < ?
            """;

    private final JdbcTemplate jdbc;
    private final CopilotProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public OutboxCleanupJob(JdbcTemplate jdbc, CopilotProperties properties) {
        this(jdbc, properties, Clock.systemUTC());
    }

    public OutboxCleanupJob(JdbcTemplate jdbc, CopilotProperties properties, Clock clock) {
        this(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                properties, clock);
    }

    public OutboxCleanupJob(JdbcTemplate jdbc, TransactionTemplate transactions,
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
        LOG.info("outbox retention cleanup completed: deletedCount={}", total);
        return total;
    }

    private int cleanBatch(Instant now) {
        Instant normalizedNow = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant cutoff = normalizedNow.minus(properties.jobs().publishedOutboxRetention());
        return requireCount(transactions.execute(status -> {
            List<byte[]> ids = jdbc.query(FIND_ELIGIBLE,
                    (resultSet, rowNum) -> resultSet.getBytes("id"),
                    Timestamp.from(cutoff), properties.jobs().batchSize());
            int deleted = 0;
            for (byte[] id : ids) {
                deleted += jdbc.update(DELETE_ELIGIBLE, id, Timestamp.from(cutoff));
            }
            return deleted;
        }));
    }

    private static int requireCount(Integer count) {
        return count == null ? 0 : count;
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
