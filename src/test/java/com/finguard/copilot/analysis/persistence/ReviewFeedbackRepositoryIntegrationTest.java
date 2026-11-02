package com.finguard.copilot.analysis.persistence;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD="
})
@Testcontainers
class ReviewFeedbackRepositoryIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10");

    @Autowired ReviewFeedbackRepository repository;
    @Autowired AnalysisRunRepository runs;
    @Autowired JdbcTemplate jdbc;

    @Test void concurrentSubmissionsProduceOneImmutableScopedFeedback() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] bytes = JdbcAnalysisRunRepository.uuidBytes(id);
        jdbc.update("""
                INSERT INTO analysis_run (id,status,reviewer_username,review_task_id,
                    review_task_version,idempotency_key,request_hash,created_at,updated_at)
                VALUES (?,'SUCCEEDED','alice',123,1,?,?,?,?)
                """, bytes, id.toString(), new byte[32],
                java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
        jdbc.update("""
                INSERT INTO analysis_result (analysis_id,schema_version,recommendation,result_json)
                VALUES (?,1,'CONFIRMED','{}')
                """, bytes);
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> submitAfterGate(gate, id, "AGREE"));
            var second = pool.submit(() -> submitAfterGate(gate, id, "DISAGREE"));
            gate.countDown();
            String a = first.get(20, TimeUnit.SECONDS);
            String b = second.get(20, TimeUnit.SECONDS);
            assertThat((a.equals("AGREE") && b.equals("CONFLICT"))
                    || (a.equals("CONFLICT") && b.equals("DISAGREE"))).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_feedback WHERE analysis_id=?",
                    Integer.class, bytes)).isEqualTo(1);
            String saved = jdbc.queryForObject("SELECT verdict FROM review_feedback WHERE analysis_id=?",
                    String.class, bytes);
            assertThat(repository.submit(id, "alice", saved)).isEqualTo(saved);
            assertThat(runs.findByIdAndReviewer(id, "ALICE")).isEmpty();
            assertThatThrownBy(() -> repository.submit(id, "bob", saved))
                    .isInstanceOf(CopilotException.class)
                    .satisfies(error -> assertThat(((CopilotException) error).code())
                            .isEqualTo(ErrorCode.CONFLICT));
            assertThatThrownBy(() -> repository.submit(id, "ALICE", saved))
                    .isInstanceOf(CopilotException.class)
                    .satisfies(error -> assertThat(((CopilotException) error).code())
                            .isEqualTo(ErrorCode.CONFLICT));
        } finally {
            pool.shutdownNow();
        }
    }

    private String submitAfterGate(CountDownLatch gate, UUID id, String verdict) throws Exception {
        gate.await();
        try {
            return repository.submit(id, "alice", verdict);
        } catch (CopilotException exception) {
            assertThat(exception.code()).isEqualTo(ErrorCode.CONFLICT);
            return "CONFLICT";
        }
    }
}
