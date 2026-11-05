package com.finguard.copilot.agent.graph;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.finguard.copilot.agent.checkpoint.*;
import com.finguard.copilot.agent.runtime.*;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.events.AnalysisEventRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"COPILOT_DB_URL=jdbc:mysql://unused/test","COPILOT_DB_USERNAME=test-only","COPILOT_DB_PASSWORD="})
@Testcontainers
class AgentGraphRecoveryIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10")
        .withCommand("--log-bin-trust-function-creators=1");
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    final SnapshotCodec codec=new SnapshotCodec();
    final Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    final String owner="owner-a",other="owner-b";
    UUID id;
    CheckpointStore store;
    @BeforeEach void init(){
        for(String table:List.of("analysis_message_receipt","analysis_checkpoint","outbox_event","analysis_result","analysis_input_snapshot","agent_run_policy","analysis_run"))
            jdbc.update("DELETE FROM "+table);
        store=storeAt(now);id=UUID.randomUUID();var facts=facts();
        var snapshot=codec.capture(facts);byte[] key=bytes();
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED','reviewer',42,0,?,?)",key,id.toString(),new byte[32]);
        jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) VALUES(?,1,?,?,?,?)",
            key,new String(codec.canonicalBytes(snapshot),java.nio.charset.StandardCharsets.UTF_8),snapshot.sha256(),java.sql.Timestamp.from(snapshot.capturedAt()),java.sql.Timestamp.from(snapshot.expiresAt()));
        jdbc.update("INSERT INTO agent_run_policy(analysis_id,workflow_version,snapshot_sha256,index_version,runtime_enabled) VALUES(?,'agent-v1',?,'frozen',TRUE)",key,snapshot.sha256());
        jdbc.update("INSERT IGNORE INTO knowledge_index_version(index_version,status,published_at) VALUES('frozen','PUBLISHED',CURRENT_TIMESTAMP(6))");
    }
    CheckpointStore storeAt(Instant at){return new CheckpointStore(jdbc,tx,new AnalysisEventRepository(jdbc),codec,Clock.fixed(at,ZoneOffset.UTC),Duration.ofMinutes(2));}
    @Test void checkpointCasEventsAndNextOutboxAdvanceTogether(){
        var first=store.acquire(id,owner).orElseThrow();
        assertThat(first.revision()).isZero();
        assertThat(store.acquire(id,other)).isEmpty();
        assertThat(store.compareAndAdvance(id,99,owner,AgentWorkingState.initial().withNode(AgentNode.PLAN),false)).isFalse();
        assertThat(store.compareAndAdvance(id,0,owner,AgentWorkingState.initial().withNode(AgentNode.PLAN),false)).isTrue();
        assertThat(store.read(id).orElseThrow().revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE analysis_id=?",Integer.class,bytes())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id=?",String.class,bytes())).isEqualTo("PLANNING");
        assertThat(new AnalysisEventRepository(jdbc).head(id)).isGreaterThan(0);
        var recorded=new AnalysisEventRepository(jdbc).after(id,0,64);
        assertThat(recorded.get(recorded.size()-1).agentRevision()).isEqualTo("1");
        assertThat(recorded.get(recorded.size()-1).agentNode()).isEqualTo("PLAN");
    }
    @Test void failedOutboxCommitCannotLeaveAdvancedCheckpointOrEvent(){
        store.acquire(id,owner);long head=new AnalysisEventRepository(jdbc).head(id);
        jdbc.execute("CREATE TRIGGER fail_graph_outbox BEFORE INSERT ON outbox_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture'");
        try{assertThatThrownBy(()->store.compareAndAdvance(id,0,owner,AgentWorkingState.initial().withNode(AgentNode.PLAN),false));}
        finally{jdbc.execute("DROP TRIGGER fail_graph_outbox");}
        assertThat(store.read(id).orElseThrow().revision()).isZero();
        assertThat(new AnalysisEventRepository(jdbc).head(id)).isEqualTo(head);
    }
    @Test void expiredOwnerCannotAdvanceAfterAnotherWorkerClaimsTheRun(){
        store.acquire(id,owner);
        var later=storeAt(now.plusSeconds(121));
        assertThat(later.acquire(id,other)).isPresent();
        assertThat(store.compareAndAdvance(id,0,owner,AgentWorkingState.initial().withNode(AgentNode.PLAN),false)).isFalse();
        assertThat(later.compareAndAdvance(id,0,other,AgentWorkingState.initial().withNode(AgentNode.PLAN),false)).isTrue();
    }
    @Test void waitingStatePersistsAndExpiresWithoutLosingAudit(){
        store.acquire(id,owner);
        assertThat(store.compareAndAdvance(id,0,owner,AgentWorkingState.initial().withNode(AgentNode.TOOLS),true)).isTrue();
        var waiting=store.read(id).orElseThrow();
        assertThat(waiting.waiting()).isTrue();assertThat(waiting.waitExpiresAt()).isBeforeOrEqualTo(now.plus(Duration.ofHours(24)));
        assertThat(store.acquire(id,other)).isEmpty();
        assertThat(storeAt(now.plus(Duration.ofDays(2))).expireWaiting(100)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT failure_code FROM analysis_run WHERE id=?",String.class,bytes())).isEqualTo("WAIT_EXPIRED");
    }
    @Test void corruptedOrUnknownVersionCheckpointIsNotExecuted(){
        store.acquire(id,owner);
        jdbc.update("UPDATE analysis_checkpoint SET memory_sha256=? WHERE analysis_id=?","0".repeat(64),bytes());
        assertThatThrownBy(()->store.read(id)).hasMessageContaining("checkpoint");
    }
    @Test void unownedPoisonedCheckpointIsQuarantinedWithoutHotRedelivery(){
        store.acquire(id,owner);
        jdbc.update("UPDATE analysis_checkpoint SET owner_token=NULL,lease_until=NULL,memory_sha256=? WHERE analysis_id=?","0".repeat(64),bytes());
        var runner=mock(BoundedAgentRunner.class);
        var graph=new ReviewAgentGraph(runner,store,false,com.finguard.copilot.observability.AnalysisTelemetry.noop());
        var message=new com.finguard.copilot.dispatch.AnalysisRequestedMessage(1,UUID.randomUUID(),id,now);
        assertThatCode(()->graph.process(message)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT failure_code FROM analysis_run WHERE id=?",String.class,bytes())).isEqualTo("AGENT_CHECKPOINT_INVALID");
        assertThat(store.hasReceipt(message.eventId())).isTrue();
        assertThat(graph.process(message)).isEqualTo(com.finguard.copilot.dispatch.AnalysisWorker.WorkerOutcome.NOOP);
        verifyNoInteractions(runner);
        var trace=new AgentTraceService(null,store,new com.finguard.copilot.agent.budget.AgentCallLedger(jdbc,tx),jdbc,new org.springframework.mock.env.MockEnvironment());
        var summary=trace.summaryAfterAuthorization(id);
        assertThat(summary.node()).isEqualTo("INVALID");
        assertThat(summary.replayable()).isFalse();assertThat(summary.canResume()).isFalse();
    }
    @Test void expiredOwnerCannotFailRunEvenBeforeTakeover(){
        store.acquire(id,owner);
        assertThat(storeAt(now.plusSeconds(121)).failOwned(id,owner,"AGENT_STEP_UNCERTAIN",UUID.randomUUID())).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id=?",String.class,bytes())).isNotEqualTo("FAILED");
    }
    @Test void activeOtherOwnerOfPoisonedCheckpointCannotBeFailedByUninitializedDelivery(){
        store.acquire(id,other);
        jdbc.update("UPDATE analysis_checkpoint SET memory_sha256=? WHERE analysis_id=?","0".repeat(64),bytes());
        store.failUninitialized(id,"AGENT_CHECKPOINT_INVALID",UUID.randomUUID());
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id=?",String.class,bytes())).isNotEqualTo("FAILED");
    }
    @Test void expiredLeaseCreatesOneDurableRecoveryEvent(){
        store.acquire(id,owner);
        var later=storeAt(now.plusSeconds(121));
        assertThat(later.requeueExpired(100)).isEqualTo(1);
        assertThat(later.requeueExpired(100)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE analysis_id=?",Integer.class,bytes())).isEqualTo(1);
    }
    @Test void compiledGraphPausesBeforeToolsThenContinuesFromDurableNode(){
        var runner=mock(BoundedAgentRunner.class);
        when(runner.step(org.mockito.ArgumentMatchers.eq(id),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(AgentExecutionFence.class))).thenAnswer(call->{
            AgentWorkingState state=call.getArgument(1);
            AgentExecutionFence fence=call.getArgument(2);var durable=store.read(id).orElseThrow();
            assertThat(fence.owner()).isEqualTo(durable.owner());assertThat(fence.revision()).isEqualTo(durable.revision());
            return switch(state.node()){
                case RETRIEVE -> state.withNode(AgentNode.PLAN);
                case PLAN -> state.planned(List.of(new com.finguard.copilot.agent.model.AgentToolCall("one","get_task_state",new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())));
                case TOOLS -> state.withNode(AgentNode.GENERATE);
                case GENERATE -> state.candidate(new com.finguard.copilot.analysis.domain.AnalysisResult(1,id,"summary","rule",
                    List.of(new com.finguard.copilot.analysis.domain.AnalysisResult.BusinessEvidence(2,3,new BigDecimal("100.123456789012345678"),"CNY","LARGE_AMOUNT","REVIEW",List.of("transaction.amount"))),
                    List.of(),com.finguard.copilot.analysis.domain.AnalysisRecommendation.INSUFFICIENT_EVIDENCE,"missing policy",List.of("policy"),List.of("snapshot")));
                case COMPLETE -> state.withNode(AgentNode.DONE);
                default -> state;
            };
        });
        var graph=new ReviewAgentGraph(runner,store,true,com.finguard.copilot.observability.AnalysisTelemetry.noop());
        var first=new com.finguard.copilot.dispatch.AnalysisRequestedMessage(1,UUID.randomUUID(),id,now);
        graph.process(first);
        assertThat(store.read(id).orElseThrow().waiting()).isTrue();
        verify(runner,never()).step(org.mockito.ArgumentMatchers.eq(id),org.mockito.ArgumentMatchers.argThat(state->state.node()==AgentNode.TOOLS),org.mockito.ArgumentMatchers.any(AgentExecutionFence.class));
        graph.process(first);
        tx.executeWithoutResult(transaction->{
            store.lock(id);
            jdbc.update("UPDATE analysis_checkpoint SET waiting=FALSE,wait_expires_at=NULL,human_approved=TRUE,revision=revision+1 WHERE analysis_id=?",bytes());
            store.status(id,com.finguard.copilot.analysis.domain.AnalysisStatus.PLANNING,null);
        });
        graph.process(new com.finguard.copilot.dispatch.AnalysisRequestedMessage(1,UUID.randomUUID(),id,now));
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id=?",String.class,bytes())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_result WHERE analysis_id=?",Integer.class,bytes())).isEqualTo(1);
    }
    @Test void corruptedCheckpointCannotBlockRecoveryForOtherRuns(){
        store.acquire(id,owner);
        jdbc.update("UPDATE analysis_checkpoint SET memory_sha256=? WHERE analysis_id=?","0".repeat(64),bytes());
        UUID healthy=UUID.randomUUID();byte[] key=JdbcAnalysisRunRepository.uuidBytes(healthy);
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) SELECT ?,status,reviewer_username,review_task_id,review_task_version,?,request_hash FROM analysis_run WHERE id=?",key,healthy.toString(),bytes());
        jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) SELECT ?,schema_version,facts_json,facts_sha256,captured_at,expires_at FROM analysis_input_snapshot WHERE analysis_id=?",key,bytes());
        jdbc.update("INSERT INTO agent_run_policy(analysis_id,workflow_version,snapshot_sha256,index_version,runtime_enabled) SELECT ?,workflow_version,snapshot_sha256,index_version,TRUE FROM agent_run_policy WHERE analysis_id=?",key,bytes());
        store.acquire(healthy,other);
        assertThatCode(()->storeAt(now.plusSeconds(121)).requeueExpired(100)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT failure_code FROM analysis_run WHERE id=?",String.class,bytes())).isEqualTo("AGENT_CHECKPOINT_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE analysis_id=?",Integer.class,key)).isEqualTo(1);
    }
    byte[] bytes(){return JdbcAnalysisRunRepository.uuidBytes(id);}
    ReviewTaskContext facts(){return new ReviewTaskContext(new ReviewTaskContext.ReviewTask(42,0,"PENDING","LARGE_AMOUNT","REVIEW",now),
        new ReviewTaskContext.Transaction(2,3,new BigDecimal("100.123456789012345678"),"CNY",now,"CSV_IMPORT"),
        new ReviewTaskContext.Reconciliation("SUSPICIOUS","NONE","REVIEW"),List.of(),new ReviewTaskContext.AccountSummary("ACTIVE","Primary"),now);}
}
