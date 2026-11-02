package com.finguard.copilot.context;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ExactJsonMigrationIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10");
    private final SnapshotCodec codec=new SnapshotCodec();
    private final Instant now=Instant.parse("2026-11-02T00:00:00Z");

    @Test void upgradesExistingJsonWithoutRehashingAndPreservesNewExactNumbers() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("7").load().migrate();
        UUID legacy=UUID.randomUUID(),damaged=UUID.randomUUID(),precise=UUID.randomUUID();
        var old=codec.capture(facts("100.25"));
        var exact=codec.capture(facts("99.999999999999999999"));
        insert(jdbc,legacy,old,true);insert(jdbc,damaged,exact,true);
        jdbc.update("INSERT INTO analysis_result(analysis_id,schema_version,recommendation,result_json) VALUES (?,1,'CONFIRMED',CAST(? AS JSON))",
                JdbcAnalysisRunRepository.uuidBytes(legacy),"{\"amount\":100.25}");
        Flyway.configure().dataSource(source).load().migrate();
        String oldJson=read(jdbc,"analysis_input_snapshot","facts_json",legacy);
        assertThat(codec.decodeAndVerify(oldJson.getBytes(StandardCharsets.UTF_8),old.sha256()).transaction().amount()).isEqualByComparingTo("100.25");
        String brokenJson=read(jdbc,"analysis_input_snapshot","facts_json",damaged);
        assertThatThrownBy(()->codec.decodeAndVerify(brokenJson.getBytes(StandardCharsets.UTF_8),exact.sha256()))
                .isInstanceOf(IllegalArgumentException.class);
        insert(jdbc,precise,exact,false);
        String newJson=read(jdbc,"analysis_input_snapshot","facts_json",precise);
        assertThat(codec.decodeAndVerify(newJson.getBytes(StandardCharsets.UTF_8),exact.sha256()).transaction().amount())
                .isEqualByComparingTo("99.999999999999999999");
        var repository=new JdbcAnalysisRunRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc));
        repository.insertResult(precise,1,"IGNORED","{\"amount\":99.999999999999999999}",now);
        assertThat(read(jdbc,"analysis_result","result_json",precise)).contains("99.999999999999999999");
        assertThatThrownBy(()->jdbc.update("UPDATE analysis_result SET result_json=? WHERE analysis_id=?","{broken",JdbcAnalysisRunRepository.uuidBytes(precise)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE analysis_input_snapshot SET facts_json=? WHERE analysis_id=?","{broken",JdbcAnalysisRunRepository.uuidBytes(precise)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    private void insert(JdbcTemplate jdbc,UUID id,FactSnapshot snapshot,boolean legacy) {
        byte[] bytes=JdbcAnalysisRunRepository.uuidBytes(id);
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES (?,'CONTEXT_READY','test',1,0,?,?)",
                bytes,id.toString(),new byte[32]);
        String value=legacy?"CAST(? AS JSON)":"?";
        jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) VALUES (?,1,"+value+",?,?,?)",
                bytes,new String(codec.canonicalBytes(snapshot),StandardCharsets.UTF_8),snapshot.sha256(),Timestamp.from(now),Timestamp.from(snapshot.expiresAt()));
    }
    private String read(JdbcTemplate jdbc,String table,String column,UUID id) {
        return jdbc.queryForObject("SELECT "+column+" FROM "+table+" WHERE analysis_id=?",String.class,JdbcAnalysisRunRepository.uuidBytes(id));
    }
    private ReviewTaskContext facts(String amount) {
        return new ReviewTaskContext(new ReviewTaskContext.ReviewTask(1,0,"PENDING","TEST","SYNTH",now),
                new ReviewTaskContext.Transaction(11,42,new BigDecimal(amount),"USD",now,"SYNTHETIC"),
                new ReviewTaskContext.Reconciliation("MATCHED","REFERENCE_ID","SYNTH"),List.of(),new ReviewTaskContext.AccountSummary("ACTIVE","test"),now);
    }
}
