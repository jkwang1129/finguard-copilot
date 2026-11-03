package com.finguard.copilot.events;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class AnalysisEventMigrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10");
    @Test void upgradesLegacyRunsWithOneSnapshotAndContinuesAtSequenceTwo() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(ds).target("11").load().migrate();var jdbc=new JdbcTemplate(ds);
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash,failure_code) VALUES(UNHEX(REPEAT('a',32)),'FAILED','legacy',1,0,'legacy',UNHEX(REPEAT('0',64)),'raw-secret')");
        Flyway.configure().dataSource(ds).load().migrate();
        var repository=new AnalysisEventRepository(jdbc);var id=java.util.UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        var snapshot=repository.after(id,0,64);
        assertThat(snapshot).hasSize(1);assertThat(snapshot.get(0).type()).isEqualTo("SNAPSHOT");
        assertThat(snapshot.get(0).failureCode()).isEqualTo("INTERNAL_ERROR");
        repository.append(id,com.finguard.copilot.analysis.domain.AnalysisStatus.FAILED,"INTERNAL_ERROR",java.time.Instant.now(),"STATE_CHANGED");
        assertThat(repository.after(id,1,64)).extracting(AnalysisEventRepository.Event::sequence).containsExactly("2");
    }
}
