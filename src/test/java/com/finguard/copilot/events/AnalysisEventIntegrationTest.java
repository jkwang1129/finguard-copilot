package com.finguard.copilot.events;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.finguard.copilot.analysis.domain.*;
import com.finguard.copilot.analysis.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"COPILOT_DB_URL=jdbc:mysql://unused/test","COPILOT_DB_USERNAME=test-only","COPILOT_DB_PASSWORD="})
@Testcontainers
class AnalysisEventIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10");
    @Autowired JdbcAnalysisRunRepository runs;
    @Autowired AnalysisEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    UUID seed() {
        UUID id=UUID.randomUUID();Instant now=Instant.now();
        runs.insert(new AnalysisRun(id,AnalysisStatus.CONTEXT_READY,"event-reviewer",1,0,id.toString(),new byte[32],null,true,now,now));
        return id;
    }
    @Test void stateAndEventCommitTogetherAndCasNoopDoesNotAppend() {
        var id=seed();assertThat(events.after(id,0,64)).hasSize(1);
        assertThat(runs.updateStatus(id,AnalysisStatus.CONTEXT_READY,AnalysisStatus.QUEUED,null,Instant.now())).isEqualTo(1);
        assertThat(runs.updateStatus(id,AnalysisStatus.CONTEXT_READY,AnalysisStatus.QUEUED,null,Instant.now())).isZero();
        assertThat(events.after(id,0,64)).extracting(AnalysisEventRepository.Event::sequence).containsExactly("1","2");
        assertThat(events.after(id,1,64)).extracting(AnalysisEventRepository.Event::status).containsExactly(AnalysisStatus.QUEUED);
        tx.executeWithoutResult(s->{runs.updateStatus(id,AnalysisStatus.QUEUED,AnalysisStatus.GENERATING,null,Instant.now());s.setRollbackOnly();});
        assertThat(events.head(id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id=?",String.class,JdbcAnalysisRunRepository.uuidBytes(id))).isEqualTo("QUEUED");
    }
    @Test void concurrentAppendsHaveNoDuplicateOrMissingSequence() throws Exception {
        var id=seed();var pool=Executors.newFixedThreadPool(4);
        try {
            var jobs=new ArrayList<Future<?>>();
            for(int i=0;i<12;i++) jobs.add(pool.submit(()->events.append(id,AnalysisStatus.GENERATING,null,Instant.now(),"STATE_CHANGED")));
            for(var job:jobs) job.get(10,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertThat(events.after(id,0,64)).extracting(AnalysisEventRepository.Event::sequence)
                .containsExactly(java.util.stream.LongStream.rangeClosed(1,13).mapToObj(Long::toString).toArray(String[]::new));
    }
    @Test void invalidFailureTextIsMappedToAControlledCode() {
        var id=seed();events.append(id,AnalysisStatus.FAILED,"Bearer SECRET\nraw prompt",Instant.now(),"STATE_CHANGED");
        var event=events.after(id,1,64).get(0);
        assertThat(event.failureCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(event.resultAvailable()).isFalse();assertThat(event.toString()).doesNotContain("SECRET");
    }
}
