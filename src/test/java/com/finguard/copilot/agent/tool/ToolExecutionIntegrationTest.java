package com.finguard.copilot.agent.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.governance.CanonicalJson;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
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
class ToolExecutionIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10");
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    final SnapshotCodec codec=new SnapshotCodec();
    KnowledgeRetrievalService retrieval;
    ToolExecutor executor;
    UUID id;
    FactSnapshot snapshot;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void init() {
        retrieval=mock(KnowledgeRetrievalService.class);
        executor=new ToolExecutor(jdbc,tx,codec,retrieval,new ToolRegistry(),Clock.systemUTC());
        id=UUID.randomUUID();
        var now=Instant.now();
        var facts=new ReviewTaskContext(new ReviewTaskContext.ReviewTask(42,0,"PENDING","LARGE_AMOUNT","REVIEW",now),
            new ReviewTaskContext.Transaction(2,3,new BigDecimal("100.123456789012345678"),"CNY",now,"CSV_IMPORT"),
            new ReviewTaskContext.Reconciliation("SUSPICIOUS","NONE","REVIEW"),List.of(),
            new ReviewTaskContext.AccountSummary("ACTIVE","Primary"),now);
        snapshot=codec.capture(facts);
        var bytes=JdbcAnalysisRunRepository.uuidBytes(id);
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED','reviewer',42,0,?,?)",bytes,id.toString(),new byte[32]);
        jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) VALUES(?,1,?,?,?,?)",
            bytes,new String(codec.canonicalBytes(snapshot),java.nio.charset.StandardCharsets.UTF_8),snapshot.sha256(),
            java.sql.Timestamp.from(snapshot.capturedAt()),java.sql.Timestamp.from(snapshot.expiresAt()));
        jdbc.update("INSERT INTO agent_run_policy(analysis_id,workflow_version,snapshot_sha256,index_version) VALUES(?,'agent-v1',?,'frozen')",bytes,snapshot.sha256());
        jdbc.update("INSERT IGNORE INTO knowledge_index_version(index_version,status,published_at) VALUES('frozen','PUBLISHED',CURRENT_TIMESTAMP(6))");
        jdbc.update("UPDATE knowledge_index_version SET status='PUBLISHED' WHERE index_version='frozen'");
    }
    ToolExecutionContext context(String key) {
        return new ToolExecutionContext(id,"reviewer",HexFormat.of().formatHex(snapshot.sha256()),"frozen",key);
    }
    ToolInvocation invoke(String name) {return new ToolInvocation(name,json.createObjectNode());}
    @Test void frozenGraphCannotReadOrConsumeSlotWithoutItsCurrentExecutionFence(){
        jdbc.update("UPDATE agent_run_policy SET runtime_enabled=TRUE WHERE analysis_id=?",bytes());
        assertThatThrownBy(()->executor.execute(context("unfenced"),invoke("get_task_state"))).hasMessageContaining("lease");
        assertThat(count()).isZero();
    }
    @Test void takeoverRevisionAndExpiryAreCheckedBeforeToolClaim(){
        jdbc.update("UPDATE agent_run_policy SET runtime_enabled=TRUE WHERE analysis_id=?",bytes());
        jdbc.update("INSERT INTO analysis_checkpoint(analysis_id,schema_version,workflow_version,revision,next_node,snapshot_sha256,index_version,owner_token,lease_until) VALUES(?,1,'agent-v1',7,'TOOLS',?,'frozen','new-owner',?)",bytes(),snapshot.sha256(),java.sql.Timestamp.from(Instant.now().plusSeconds(120)));
        for(var fence:List.of(new com.finguard.copilot.agent.runtime.AgentExecutionFence("old-owner",7),new com.finguard.copilot.agent.runtime.AgentExecutionFence("new-owner",6))){
            var scope=new ToolExecutionContext(id,"reviewer",context("race").snapshotHash(),"frozen","race",null,null,fence);
            assertThatThrownBy(()->executor.execute(scope,invoke("get_task_state"))).hasMessageContaining("lease");
        }
        assertThat(count()).isZero();
        var scope=new ToolExecutionContext(id,"reviewer",context("live").snapshotHash(),"frozen","live",null,null,new com.finguard.copilot.agent.runtime.AgentExecutionFence("new-owner",7));
        jdbc.update("UPDATE analysis_checkpoint SET lease_until=? WHERE analysis_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),bytes());
        assertThatThrownBy(()->executor.execute(scope,invoke("get_task_state"))).hasMessageContaining("lease");
        assertThat(count()).isZero();
        jdbc.update("UPDATE analysis_checkpoint SET lease_until=? WHERE analysis_id=?",java.sql.Timestamp.from(Instant.now().plusSeconds(120)),bytes());
        executor.execute(scope,invoke("get_task_state"));assertThat(count()).isEqualTo(1);
    }
    @Test void storesExactSnapshotAndReplaysWithoutExecutingAgain() {
        var first=executor.execute(context("one"),invoke("get_review_facts"));
        var replay=executor.execute(context("one"),invoke("get_review_facts"));
        assertThat(CanonicalJson.encode(first)).isEqualTo(CanonicalJson.encode(replay));
        assertThat(first.data().path("transaction").path("amount").asText()).isEqualTo("100.123456789012345678");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_tool_execution WHERE analysis_id=?",String.class,bytes())).isEqualTo("SUCCEEDED");
    }
    @Test void sharedExecutorExportsAControlledToolSpanWithoutResultOrArguments(){
        var exporter=io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
        try(var provider=io.opentelemetry.sdk.trace.SdkTracerProvider.builder().addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build()){
            executor.telemetry(new com.finguard.copilot.observability.AnalysisTelemetry(provider.get("test"),null));
            executor.execute(context("trace"),invoke("get_task_state"));
            var span=exporter.getFinishedSpanItems().get(0);
            assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("finguard.stage"))).isEqualTo("AGENT_TOOL");
            assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("finguard.tool_name"))).isEqualTo("get_task_state");
            assertThat(span.getAttributes().toString()).doesNotContain("100.123456789012345678","CORE_SNAPSHOT","arguments");
        }
    }
    @Test void oneStepCannotChangeItsNameOrArguments() {
        executor.execute(context("one"),invoke("get_task_state"));
        assertThatThrownBy(()->executor.execute(context("one"),invoke("get_review_facts"))).hasMessageContaining("conflict");
        assertThat(count()).isEqualTo(1);
    }
    @Test void validAndRejectedRequestsShareFourSlots() {
        assertThatThrownBy(()->executor.execute(context("bad"),invoke("http_get"))).hasMessageContaining("Tool");
        for(int n=0;n<3;n++) executor.execute(context("step"+n),invoke("get_task_state"));
        assertThatThrownBy(()->executor.execute(context("fifth"),invoke("get_task_state"))).hasMessageContaining("budget");
        assertThat(count()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_tool_execution WHERE analysis_id=? AND status='REJECTED'",Integer.class,bytes())).isEqualTo(1);
    }
    @Test void rejectsCrossOwnerForgedSnapshotOrIndexBeforeAllocating() {
        for(var bad:List.of(new ToolExecutionContext(id,"other",context("a").snapshotHash(),"frozen","a"),
            new ToolExecutionContext(id,"reviewer","0".repeat(64),"frozen","b"),
            new ToolExecutionContext(id,"reviewer",context("c").snapshotHash(),"other","c"))) {
            assertThatThrownBy(()->executor.execute(bad,invoke("get_review_facts")));
        }
        assertThat(count()).isZero();
    }
    @Test void expiredDeletedOrCorruptSnapshotsCannotExecute() {
        executor=new ToolExecutor(jdbc,tx,codec,retrieval,new ToolRegistry(),
            Clock.offset(Clock.systemUTC(),Duration.ofDays(31)));
        assertThatThrownBy(()->executor.execute(context("a"),invoke("get_review_facts")));
        assertThat(count()).isZero();
    }
    @Test void policyUsesOnlyPinnedIndexAndRejectsBackendIndexMismatch() throws Exception {
        when(retrieval.retrievePinned("frozen","risk",1)).thenReturn(
            new KnowledgeRetrievalService.RetrievalBatch("other",List.of()));
        assertThatThrownBy(()->executor.execute(context("search"),new ToolInvocation(
            "search_review_policy",json.readTree("{\"query\":\"risk\",\"topK\":1}")))).hasMessageContaining("Tool");
        verify(retrieval).retrievePinned("frozen","risk",1);
        assertThat(count()).isEqualTo(1);
    }
    @Test void concurrentWorkersCannotAllocateMoreThanFourSlots() throws Exception {
        var pool=Executors.newFixedThreadPool(6);
        var start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Boolean>>();
            for(int n=0;n<6;n++){final int slot=n; futures.add(pool.submit(()->{
                start.await();try{executor.execute(context("p"+slot),invoke("get_task_state"));return true;}
                catch(com.finguard.copilot.common.error.CopilotException expected){return false;}
            }));}
            start.countDown();
            int successes=0; for(var f:futures)if(f.get(30,TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(4);assertThat(count()).isEqualTo(4);
        } finally {pool.shutdownNow();}
    }
    @Test void claimedExecutionIsNeverRetriedEvenWhenOutcomeWasLost() {
        jdbc.update("INSERT INTO analysis_tool_execution(analysis_id,slot,step_key,tool_name,arguments_sha256,status) VALUES(?,1,'lost','get_task_state',?,'CLAIMED')",
            bytes(),CanonicalJson.hash(Map.of("toolName","get_task_state","arguments",json.createObjectNode())));
        assertThatThrownBy(()->executor.execute(context("lost"),invoke("get_task_state"))).hasMessageContaining("unknown");
        assertThat(count()).isEqualTo(1);verifyNoInteractions(retrieval);
    }
    @Test void withdrawnIndexCannotReplayPreviouslySuccessfulSearch() throws Exception {
        when(retrieval.retrievePinned("frozen","risk",1)).thenReturn(
            new KnowledgeRetrievalService.RetrievalBatch("frozen",List.of()));
        var request=new ToolInvocation("search_review_policy",json.readTree("{\"query\":\"risk\",\"topK\":1}"));
        executor.execute(context("published"),request);
        jdbc.update("UPDATE knowledge_index_version SET status='RETIRED' WHERE index_version='frozen'");
        assertThatThrownBy(()->executor.execute(context("published"),request)).hasMessageContaining("index");
        verify(retrieval,times(1)).retrievePinned("frozen","risk",1);
    }
    @Test void corruptOrDeletedSnapshotDoesNotAllocateOrReplay() {
        executor.execute(context("ok"),invoke("get_task_state"));
        jdbc.update("UPDATE analysis_input_snapshot SET facts_json='{}' WHERE analysis_id=?",bytes());
        assertThatThrownBy(()->executor.execute(context("ok"),invoke("get_task_state")));
        assertThat(count()).isEqualTo(1);
        jdbc.update("UPDATE analysis_input_snapshot SET deleted_at=expires_at,facts_json=NULL WHERE analysis_id=?",bytes());
        assertThatThrownBy(()->executor.execute(context("new"),invoke("get_task_state")));
        assertThat(count()).isEqualTo(1);
    }
    @Test void cleanupDuringExternalReadCannotBeUndoneByLateToolPersistence() throws Exception {
        when(retrieval.retrievePinned("frozen","risk",1)).thenAnswer(call->{
            tx.executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM analysis_run WHERE id=? FOR UPDATE",byte[].class,bytes());
                jdbc.update("UPDATE analysis_input_snapshot SET facts_json=NULL,deleted_at=expires_at WHERE analysis_id=?",bytes());
                jdbc.update("UPDATE analysis_tool_execution SET result_json=NULL WHERE analysis_id=?",bytes());
            });
            return new KnowledgeRetrievalService.RetrievalBatch("frozen",List.of());
        });
        assertThatThrownBy(()->executor.execute(context("late"),new ToolInvocation("search_review_policy",json.readTree("{\"query\":\"risk\",\"topK\":1}"))));
        assertThat(jdbc.queryForObject("SELECT result_json FROM analysis_tool_execution WHERE analysis_id=?",String.class,bytes())).isNull();
    }
    int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM analysis_tool_execution WHERE analysis_id=?",Integer.class,bytes());}
    byte[] bytes(){return JdbcAnalysisRunRepository.uuidBytes(id);}
}
