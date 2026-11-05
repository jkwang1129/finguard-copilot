package com.finguard.copilot.agent.hitl;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.agent.checkpoint.*;
import com.finguard.copilot.agent.runtime.*;
import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.*;
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
class ResumeAnalysisIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10");
    @Autowired JdbcTemplate jdbc;@Autowired TransactionTemplate tx;
    final CoreClient core=mock(CoreClient.class);final SnapshotCodec codec=new SnapshotCodec();
    final Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    UUID id;CheckpointStore store;ResumeAnalysisService service;
    @BeforeEach void init(){
        id=UUID.randomUUID();var snapshot=codec.capture(facts(0,"PENDING","100.123456789012345678",now));
        byte[] key=bytes();
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED','reviewer',42,0,?,?)",key,id.toString(),new byte[32]);
        jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) VALUES(?,1,?,?,?,?)",
            key,new String(codec.canonicalBytes(snapshot),java.nio.charset.StandardCharsets.UTF_8),snapshot.sha256(),java.sql.Timestamp.from(snapshot.capturedAt()),java.sql.Timestamp.from(snapshot.expiresAt()));
        jdbc.update("INSERT INTO agent_run_policy(analysis_id,workflow_version,snapshot_sha256,index_version,runtime_enabled) VALUES(?,'agent-v1',?,'hitl-frozen',TRUE)",key,snapshot.sha256());
        jdbc.update("INSERT IGNORE INTO knowledge_index_version(index_version,status,published_at) VALUES('hitl-frozen','PUBLISHED',CURRENT_TIMESTAMP(6))");
        store=storeAt(now);store.acquire(id,"owner").orElseThrow();
        store.compareAndAdvance(id,0,"owner",AgentWorkingState.initial().withNode(AgentNode.TOOLS),true);
        doReturn(new CurrentUser("reviewer",Set.of("REVIEWER"))).when(core).currentUser("token");
        when(core.reviewTaskContext(42,"token")).thenReturn(facts(0,"PENDING","100.123456789012345678",now.plusSeconds(5)));
        when(core.reviewTaskState(42,"token")).thenReturn(new ReviewTaskState(42,0,"PENDING","LARGE_AMOUNT","REVIEW"));
        service=serviceAt(now);
    }
    CheckpointStore storeAt(Instant at){return new CheckpointStore(jdbc,tx,new AnalysisEventRepository(jdbc),codec,Clock.fixed(at,ZoneOffset.UTC),Duration.ofMinutes(2));}
    ResumeAnalysisService serviceAt(Instant at){
        var access=new GetAnalysisService(core,new JdbcAnalysisRunRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)),new ObjectMapper());
        return new ResumeAnalysisService(access,core,storeAt(at),jdbc,tx,codec,Clock.fixed(at,ZoneOffset.UTC));
    }
    @Test void freshCaptureTimeAloneDoesNotChangeFactsAndDuplicateResumeIsIdempotent(){
        var resumed=service.resume(id,1,ResumeAction.CONTINUE,"key","token");
        assertThat(resumed.revision()).isEqualTo(2);assertThat(resumed.replayed()).isFalse();
        assertThat(service.resume(id,1,ResumeAction.CONTINUE,"key","token").replayed()).isTrue();
        assertThat(store.read(id).orElseThrow().humanApproved()).isTrue();
        assertThat(outbox()).isEqualTo(1);
        assertThatThrownBy(()->service.resume(id,1,ResumeAction.CANCEL,"key","token")).isInstanceOf(CopilotException.class);
    }
    @Test void staleVersionStateOrSameVersionDifferentFactsRequireNewAnalysis(){
        when(core.reviewTaskContext(42,"token")).thenReturn(facts(0,"PENDING","101",now.plusSeconds(5)));
        assertCode(()->service.resume(id,1,ResumeAction.CONTINUE,"key","token"),ErrorCode.CONTEXT_STALE);
        assertThat(jdbc.queryForObject("SELECT failure_code FROM analysis_run WHERE id=?",String.class,bytes())).isEqualTo("CONTEXT_STALE");
        assertThat(outbox()).isZero();
    }
    @Test void changedTaskVersionAndClosedStateAreRejected(){
        when(core.reviewTaskContext(42,"token")).thenReturn(facts(1,"CONFIRMED","100.123456789012345678",now));
        when(core.reviewTaskState(42,"token")).thenReturn(new ReviewTaskState(42,1,"CONFIRMED","LARGE_AMOUNT","REVIEW"));
        assertCode(()->service.resume(id,1,ResumeAction.CONTINUE,"key","token"),ErrorCode.CONTEXT_STALE);
    }
    @Test void authFailuresAndUnavailableCoreDoNotMutateWaitingRun(){
        when(core.currentUser("token")).thenReturn(new CurrentUser("reviewer",Set.of("ADMIN")));
        assertCode(()->service.resume(id,1,ResumeAction.CONTINUE,"key","token"),ErrorCode.FORBIDDEN);
        when(core.currentUser("token")).thenReturn(new CurrentUser("other",Set.of("REVIEWER")));
        assertCode(()->service.resume(id,1,ResumeAction.CONTINUE,"key","token"),ErrorCode.RESOURCE_NOT_FOUND);
        when(core.currentUser("token")).thenThrow(new CoreClientException(CoreClientException.Kind.UNAUTHENTICATED,"private",null));
        assertCode(()->service.resume(id,1,ResumeAction.CONTINUE,"key","token"),ErrorCode.UNAUTHENTICATED);
        assertThat(store.read(id).orElseThrow().waiting()).isTrue();
        doReturn(new CurrentUser("reviewer",Set.of("REVIEWER"))).when(core).currentUser("token");
        when(core.reviewTaskContext(42,"token")).thenThrow(new CoreClientException(CoreClientException.Kind.UNAVAILABLE,"private",null));
        assertCode(()->service.resume(id,1,ResumeAction.CONTINUE,"key","token"),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(store.read(id).orElseThrow().revision()).isEqualTo(1);
    }
    @Test void wrongRevisionAndExpiredWaitAreRejectedButExpiryIsCommitted(){
        assertCode(()->service.resume(id,0,ResumeAction.CONTINUE,"key","token"),ErrorCode.CONFLICT);
        assertCode(()->serviceAt(now.plus(Duration.ofDays(2))).resume(id,1,ResumeAction.CONTINUE,"late","token"),ErrorCode.WAIT_EXPIRED);
        assertThat(jdbc.queryForObject("SELECT failure_code FROM analysis_run WHERE id=?",String.class,bytes())).isEqualTo("WAIT_EXPIRED");
    }
    @Test void sourceExpiryBeforeWaitDeadlineCannotBeResumed(){
        jdbc.update("UPDATE analysis_input_snapshot SET expires_at=? WHERE analysis_id=?",java.sql.Timestamp.from(now.plusSeconds(1)),bytes());
        assertCode(()->serviceAt(now.plusSeconds(2)).resume(id,1,ResumeAction.CONTINUE,"expired-source","token"),ErrorCode.CONTEXT_STALE);
        assertThat(outbox()).isZero();
        assertThat(jdbc.queryForObject("SELECT failure_code FROM analysis_run WHERE id=?",String.class,bytes())).isEqualTo("CONTEXT_STALE");
    }
    @Test void concurrentDifferentResumeKeysProduceOnlyOneContinuation() throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->attempt(start,"one"));var b=pool.submit(()->attempt(start,"two"));start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(outbox()).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void cancellationIsTerminalAndDoesNotReleaseUnknownModelHolds(){
        jdbc.update("INSERT INTO agent_model_policy(analysis_id,policy_sha256,policy_json) VALUES(?,?,'{}')",bytes(),"0".repeat(64));
        jdbc.update("INSERT INTO agent_model_call(analysis_id,call_ordinal,manifest_sha256,manifest_json,status,reserved_cny,outcome_type) VALUES(?,1,?,'{}','UNKNOWN_HELD',0.01,'UNKNOWN')",bytes(),"0".repeat(64));
        var cancelled=service.resume(id,1,ResumeAction.CANCEL,"cancel","token");
        assertThat(cancelled.status().name()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM analysis_run WHERE id=?",String.class,bytes())).isEqualTo("ANALYSIS_CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM agent_model_call WHERE analysis_id=?",String.class,bytes())).isEqualTo("UNKNOWN_HELD");
    }
    boolean attempt(CountDownLatch start,String key)throws Exception{start.await();try{service.resume(id,1,ResumeAction.CONTINUE,key,"token");return true;}catch(CopilotException e){return false;}}
    int outbox(){return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE analysis_id=?",Integer.class,bytes());}
    void assertCode(Runnable action,ErrorCode code){assertThatThrownBy(action::run).isInstanceOf(CopilotException.class).extracting(e->((CopilotException)e).code()).isEqualTo(code);}
    byte[] bytes(){return JdbcAnalysisRunRepository.uuidBytes(id);}
    ReviewTaskContext facts(long version,String status,String amount,Instant captured){
        return new ReviewTaskContext(new ReviewTaskContext.ReviewTask(42,version,status,"LARGE_AMOUNT","REVIEW",now),
            new ReviewTaskContext.Transaction(2,3,new BigDecimal(amount),"CNY",now,"CSV_IMPORT"),
            new ReviewTaskContext.Reconciliation("SUSPICIOUS","NONE","REVIEW"),List.of(),new ReviewTaskContext.AccountSummary("ACTIVE","Primary"),captured);
    }
}
