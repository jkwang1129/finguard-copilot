package com.finguard.copilot.dispatch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OutboxRepository {

    private static final String CLAIM_DUE = """
            SELECT BIN_TO_UUID(id) AS event_id,
                   BIN_TO_UUID(analysis_id) AS analysis_id,
                   schema_version,
                   CAST(payload_json AS CHAR) AS payload_json,
                   attempt_count,
                   created_at AS occurred_at,
                   next_attempt_at
            FROM outbox_event
            WHERE published_at IS NULL
              AND next_attempt_at <= ?
            ORDER BY created_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private static final String LEASE_EVENT = """
            UPDATE outbox_event
            SET next_attempt_at = ?
            WHERE id = ? AND published_at IS NULL
            """;

    private static final String QUEUE_RUN = """
            UPDATE analysis_run
            SET status = 'QUEUED', updated_at = ?
            WHERE id = ? AND status = 'CONTEXT_READY'
            """;

    private static final String MARK_PUBLISHED = """
            UPDATE outbox_event
            SET published_at = ?, last_error_code = NULL
            WHERE id = ? AND published_at IS NULL
            """;

    private static final String RECORD_FAILURE = """
            UPDATE outbox_event
            SET attempt_count = attempt_count + 1,
                next_attempt_at = ?,
                last_error_code = ?
            WHERE (id = ? OR analysis_id = ?) AND published_at IS NULL
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private com.finguard.copilot.events.AnalysisEventRepository events;
    @org.springframework.beans.factory.annotation.Autowired
    public void setEvents(com.finguard.copilot.events.AnalysisEventRepository value){events=value;}

    @Autowired
    public OutboxRepository(JdbcTemplate jdbc) {
        this(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
    }

    public OutboxRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        events=new com.finguard.copilot.events.AnalysisEventRepository(jdbc);
    }

    public List<OutboxEvent> claimDueEvents(Instant now, int batchSize, Duration leaseDuration) {
        if (now == null || batchSize < 1 || leaseDuration == null || leaseDuration.isNegative()
                || leaseDuration.isZero()) {
            throw new IllegalArgumentException("outbox claim inputs are invalid");
        }
        Instant normalizedNow = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant leaseUntil = normalizedNow.plus(leaseDuration);
        return transactions.execute(status -> {
            List<OutboxEvent> events = jdbc.query(CLAIM_DUE, this::mapEvent,
                    Timestamp.from(normalizedNow), batchSize);
            for (OutboxEvent event : events) {
                jdbc.update(LEASE_EVENT, Timestamp.from(leaseUntil),
                        JdbcAnalysisRunRepository.uuidBytes(event.eventId()));
                int queued=jdbc.update(QUEUE_RUN, Timestamp.from(normalizedNow),
                        JdbcAnalysisRunRepository.uuidBytes(event.analysisId()));
                if(queued==1) this.events.append(event.analysisId(),com.finguard.copilot.analysis.domain.AnalysisStatus.QUEUED,null,normalizedNow,"STATE_CHANGED");
            }
            return events;
        });
    }

    public List<OutboxEvent> claimDueBatch(Instant now, int batchSize, Duration leaseDuration) {
        return claimDueEvents(now, batchSize, leaseDuration);
    }

    public int markPublished(UUID eventId, Instant publishedAt) {
        if (eventId == null || publishedAt == null) {
            throw new IllegalArgumentException("published event inputs are invalid");
        }
        return jdbc.update(MARK_PUBLISHED, Timestamp.from(publishedAt),
                JdbcAnalysisRunRepository.uuidBytes(eventId));
    }

    public int recordPublishFailure(UUID eventIdOrAnalysisId, String errorCode, Instant nextAttemptAt) {
        if (eventIdOrAnalysisId == null || errorCode == null || errorCode.isBlank()
                || errorCode.length() > 64 || nextAttemptAt == null) {
            throw new IllegalArgumentException("outbox failure inputs are invalid");
        }
        return jdbc.update(RECORD_FAILURE, Timestamp.from(nextAttemptAt), errorCode,
                JdbcAnalysisRunRepository.uuidBytes(eventIdOrAnalysisId),
                JdbcAnalysisRunRepository.uuidBytes(eventIdOrAnalysisId));
    }

    public int countUnpublished() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Integer.class);
    }

    private OutboxEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OutboxEvent(
                UUID.fromString(resultSet.getString("event_id")),
                UUID.fromString(resultSet.getString("analysis_id")),
                resultSet.getInt("schema_version"),
                resultSet.getString("payload_json"),
                resultSet.getInt("attempt_count"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getTimestamp("next_attempt_at").toInstant());
    }

    public record OutboxEvent(
            UUID eventId,
            UUID analysisId,
            int schemaVersion,
            String payloadJson,
            int attemptCount,
            Instant occurredAt,
            Instant nextAttemptAt
    ) {

        public OutboxEvent {
            if (eventId == null || analysisId == null || schemaVersion != 1
                    || payloadJson == null || payloadJson.isBlank()
                    || attemptCount < 0 || occurredAt == null || nextAttemptAt == null) {
                throw new IllegalArgumentException("outbox event fields are invalid");
            }
        }
    }
}
