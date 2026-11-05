package com.finguard.copilot.agent.budget;
import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
@Testcontainers
class AgentLedgerMigrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10");
    @Test void upgradingV12KeepsHistoricalOneCallClaimAndDoesNotEnableAgent(){
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(source).target("12").load().migrate();
        var jdbc=new JdbcTemplate(source);byte[] id=JdbcAnalysisRunRepository.uuidBytes(UUID.randomUUID());
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED','legacy',42,0,'legacy',?)",id,new byte[32]);
        jdbc.update("INSERT INTO model_call_reservation(analysis_id,reserved_at) VALUES(?,CURRENT_TIMESTAMP(6))",id);
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_call_reservation WHERE analysis_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_run_policy",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_model_call",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id=?",String.class,id)).isEqualTo("QUEUED");
    }
}
