package com.finguard.copilot.analysis.persistence;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisRun;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnalysisRunRepository implements AnalysisRunRepository {

    private static final String INSERT_RUN = """
            INSERT INTO analysis_run (
                id,
                status,
                reviewer_username,
                review_task_id,
                review_task_version,
                idempotency_key,
                request_hash,
                created_at,
                updated_at,
                failure_code,
                fully_reproducible
            ) VALUES (
                :id,
                :status,
                :reviewerUsername,
                :reviewTaskId,
                :reviewTaskVersion,
                :idempotencyKey,
                :requestHash,
                :createdAt,
                :updatedAt,
                :failureCode,
                :fullyReproducible
            )
            """;

    private static final String FIND_SCOPED_RUN = """
            SELECT BIN_TO_UUID(r.id) AS analysis_id,
                   r.status,
                   r.failure_code,
                   CAST(ar.result_json AS CHAR) AS result_json
            FROM analysis_run r
            LEFT JOIN analysis_result ar ON ar.analysis_id = r.id
            WHERE r.id = :analysisId
              AND BINARY r.reviewer_username = BINARY :reviewerUsername
            """;

    private static final String FIND_WORK = """
            SELECT BIN_TO_UUID(r.id) AS analysis_id,
                   r.status,
                   r.failure_code,
                   CAST(s.facts_json AS CHAR) AS facts_json,
                   s.facts_sha256,
                   CAST(ar.result_json AS CHAR) AS result_json
            FROM analysis_run r
            JOIN analysis_input_snapshot s ON s.analysis_id = r.id
            LEFT JOIN analysis_result ar ON ar.analysis_id = r.id
            WHERE r.id = ?
            FOR UPDATE
            """;

    private static final String HAS_RECEIPT = """
            SELECT COUNT(*) FROM analysis_message_receipt WHERE event_id = ?
            """;

    private static final String UPDATE_STATUS = """
            UPDATE analysis_run
            SET status = :next, failure_code = :failureCode, updated_at = :updatedAt
            WHERE id = :id AND status = :expected
            """;

    private static final String INSERT_RESULT = """
            INSERT INTO analysis_result (
                analysis_id, schema_version, recommendation, result_json, created_at
            ) VALUES (?, ?, ?, ?, ?)
            """;

    private static final String INSERT_RECEIPT = """
            INSERT INTO analysis_message_receipt (event_id, analysis_id, received_at)
            VALUES (?, ?, ?)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate eventTransactions;
    private com.finguard.copilot.events.AnalysisEventRepository events;
    @org.springframework.beans.factory.annotation.Autowired
    public void setEvents(com.finguard.copilot.events.AnalysisEventRepository value){events=value;}

    public JdbcAnalysisRunRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        eventTransactions=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
        events=new com.finguard.copilot.events.AnalysisEventRepository(jdbc.getJdbcTemplate());
    }

    @Override
    public int insert(AnalysisRun run) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", uuidBytes(run.id()))
                .addValue("status", run.status().name())
                .addValue("reviewerUsername", run.reviewerUsername())
                .addValue("reviewTaskId", run.reviewTaskId())
                .addValue("reviewTaskVersion", run.reviewTaskVersion())
                .addValue("idempotencyKey", run.idempotencyKey())
                .addValue("requestHash", run.requestHash())
                .addValue("createdAt", Timestamp.from(run.createdAt()))
                .addValue("updatedAt", Timestamp.from(run.updatedAt()))
                .addValue("failureCode", run.failureCode())
                .addValue("fullyReproducible", run.fullyReproducible());
        return eventTransactions.execute(tx->{
            int inserted=jdbc.update(INSERT_RUN,parameters);
            if(inserted==1) events.append(run.id(),run.status(),run.failureCode(),run.createdAt(),"STATE_CHANGED");
            return inserted;
        });
    }

    @Override
    public Optional<AnalysisView> findByIdAndReviewer(UUID analysisId, String reviewerUsername) {
        if (analysisId == null || reviewerUsername == null || reviewerUsername.isBlank()) {
            throw new IllegalArgumentException("scoped run lookup inputs are invalid");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("analysisId", uuidBytes(analysisId))
                .addValue("reviewerUsername", reviewerUsername);
        return jdbc.query(FIND_SCOPED_RUN, parameters, resultSet -> {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            return Optional.of(new AnalysisView(
                    UUID.fromString(resultSet.getString("analysis_id")),
                    AnalysisStatus.valueOf(resultSet.getString("status")),
                    resultSet.getString("failure_code"),
                    resultSet.getString("result_json")
            ));
        });
    }

    @Override
    public Optional<WorkView> findForWork(UUID analysisId) {
        if (analysisId == null) {
            throw new IllegalArgumentException("analysisId must not be null");
        }
        return jdbc.getJdbcTemplate().query(FIND_WORK, resultSet -> {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            return Optional.of(new WorkView(
                    UUID.fromString(resultSet.getString("analysis_id")),
                    AnalysisStatus.valueOf(resultSet.getString("status")),
                    resultSet.getString("failure_code"),
                    resultSet.getString("facts_json"),
                    resultSet.getBytes("facts_sha256"),
                    resultSet.getString("result_json")
            ));
        }, uuidBytes(analysisId));
    }

    @Override
    public boolean hasReceipt(UUID eventId) {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId must not be null");
        }
        return jdbc.getJdbcTemplate().queryForObject(HAS_RECEIPT, Integer.class, uuidBytes(eventId)) > 0;
    }

    @Override
    public int updateStatus(UUID analysisId, AnalysisStatus expected, AnalysisStatus next,
                            String failureCode, Instant updatedAt) {
        if (analysisId == null || expected == null || next == null || updatedAt == null) {
            throw new IllegalArgumentException("status update inputs are invalid");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("next", next.name())
                .addValue("failureCode", failureCode)
                .addValue("updatedAt", Timestamp.from(updatedAt))
                .addValue("id", uuidBytes(analysisId))
                .addValue("expected", expected.name());
        return eventTransactions.execute(tx->{
            int changed=jdbc.update(UPDATE_STATUS,parameters);
            if(changed==1) events.append(analysisId,next,failureCode,updatedAt,"STATE_CHANGED");
            return changed;
        });
    }

    @Override
    public int insertResult(UUID analysisId, int schemaVersion, String recommendation,
                            String resultJson, Instant createdAt) {
        return jdbc.getJdbcTemplate().update(INSERT_RESULT,
                uuidBytes(analysisId), schemaVersion, recommendation, resultJson, Timestamp.from(createdAt));
    }

    @Override
    public int insertReceipt(UUID eventId, UUID analysisId, Instant receivedAt) {
        return jdbc.getJdbcTemplate().update(INSERT_RECEIPT,
                uuidBytes(eventId), uuidBytes(analysisId), Timestamp.from(receivedAt));
    }

    public static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }
}
