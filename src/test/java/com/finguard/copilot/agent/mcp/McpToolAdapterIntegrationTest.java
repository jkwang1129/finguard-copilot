package com.finguard.copilot.agent.mcp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.agent.checkpoint.*;
import com.finguard.copilot.agent.hitl.*;
import com.finguard.copilot.agent.runtime.*;
import com.finguard.copilot.agent.tool.*;
import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.*;
import com.finguard.copilot.events.AnalysisEventRepository;
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
class McpToolAdapterIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10").withCommand("--log-bin-trust-function-creators=1");
    @Autowired JdbcTemplate jdbc;@Autowired TransactionTemplate tx;
    final SnapshotCodec codec=new SnapshotCodec();final Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    final CoreClient core=mock(CoreClient.class);final ObjectMapper json=new ObjectMapper();UUID id;FactSnapshot snapshot;
    CheckpointStore store;ToolExecutor executor;McpCapabilityService capabilities;McpToolAdapter adapter;GetAnalysisService access;
    @BeforeEach void init(){
        for(String table:List.of("analysis_message_receipt","analysis_checkpoint","analysis_tool_execution","outbox_event","analysis_result","analysis_input_snapshot","agent_run_policy","analysis_run"))jdbc.update("DELETE FROM "+table);
        reset(core);id=UUID.randomUUID();snapshot=codec.capture(facts("100.123456789012345678"));
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED','reviewer',42,0,?,?)",bytes(),id.toString(),new byte[32]);
        jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) VALUES(?,1,?,?,?,?)",bytes(),new String(codec.canonicalBytes(snapshot),java.nio.charset.StandardCharsets.UTF_8),snapshot.sha256(),java.sql.Timestamp.from(snapshot.capturedAt()),java.sql.Timestamp.from(snapshot.expiresAt()));
        jdbc.update("INSERT INTO agent_run_policy(analysis_id,workflow_version,snapshot_sha256,index_version,runtime_enabled) VALUES(?,'agent-v1',?,'frozen',TRUE)",bytes(),snapshot.sha256());
        jdbc.update("INSERT IGNORE INTO knowledge_index_version(index_version,status,published_at) VALUES('frozen','PUBLISHED',CURRENT_TIMESTAMP(6))");
        store=new CheckpointStore(jdbc,tx,new AnalysisEventRepository(jdbc),codec,Clock.fixed(now,ZoneOffset.UTC),Duration.ofMinutes(2));
        store.acquire(id,"owner");store.compareAndAdvance(id,0,"owner",AgentWorkingState.initial().withNode(AgentNode.TOOLS),true);
        access=new GetAnalysisService(core,new JdbcAnalysisRunRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)),json);
        when(core.currentUser("jwt")).thenReturn(new CurrentUser("reviewer",Set.of("REVIEWER")));
        when(core.reviewTaskContext(42,"jwt")).thenReturn(facts("100.123456789012345678"));
        when(core.reviewTaskState(42,"jwt")).thenReturn(new ReviewTaskState(42,0,"PENDING","LARGE_AMOUNT","REVIEW"));
        executor=new ToolExecutor(jdbc,tx,codec,mock(KnowledgeRetrievalService.class),new ToolRegistry(),Clock.fixed(now,ZoneOffset.UTC));
        capabilities=service(now);adapter=new McpToolAdapter(capabilities,executor,new ToolRegistry());
    }
    McpCapabilityService service(Instant time){return new McpCapabilityService(access,core,jdbc,tx,store,codec,Clock.fixed(time,ZoneOffset.UTC));}
    @Test void freshAuthorizationIssuesHashOnlyCapabilityAndUsesTheSharedSchemaAndExecutor(){
        var grant=capabilities.issue(id,List.of("get_task_state"),"jwt");
        var session=capabilities.authorize(id,grant.capability());
        assertThat(adapter.list(session)).hasSize(1);assertThat(adapter.list(session).get(0)).isEqualTo(new ToolRegistry().schemas().stream().filter(t->t.path("name").asText().equals("get_task_state")).findFirst().orElseThrow());
        var result=adapter.call(session,"get_task_state",json.createObjectNode(),"same");
        assertThat(com.finguard.copilot.governance.CanonicalJson.encode(adapter.call(session,"get_task_state",json.createObjectNode(),"same")))
            .isEqualTo(com.finguard.copilot.governance.CanonicalJson.encode(result));
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM analysis_mcp_capability WHERE analysis_id=?",bytes()).toString()).doesNotContain(grant.capability(),"jwt");
        assertThat(grant.toString()).doesNotContain(grant.capability());
        assertThat(grant.expiresAt()).isBeforeOrEqualTo(now.plusSeconds(300));
    }
    @Test void missingExpiredRevokedCrossAnalysisOrUnscopedCapabilitiesAreRejected(){
        var grant=capabilities.issue(id,List.of("get_task_state"),"jwt");var session=capabilities.authorize(id,grant.capability());
        assertThatThrownBy(()->capabilities.authorize(id,null)).isInstanceOf(CopilotException.class);
        assertThatThrownBy(()->capabilities.authorize(UUID.randomUUID(),grant.capability())).isInstanceOf(CopilotException.class);
        assertThatThrownBy(()->service(now.plusSeconds(301)).authorize(id,grant.capability())).isInstanceOf(CopilotException.class);
        assertThatThrownBy(()->adapter.call(session,"get_review_facts",json.createObjectNode(),"out-of-scope")).isInstanceOf(CopilotException.class);
        capabilities.revoke(id,"jwt");assertThatThrownBy(()->adapter.list(session)).isInstanceOf(CopilotException.class);assertThat(count()).isZero();
    }
    @Test void activeGraphRevisionChangeAndSameVersionFactChangeRejectAccess(){
        var grant=capabilities.issue(id,List.of("get_task_state"),"jwt");var session=capabilities.authorize(id,grant.capability());
        jdbc.update("UPDATE analysis_checkpoint SET revision=revision+1,waiting=FALSE,wait_expires_at=NULL WHERE analysis_id=?",bytes());
        jdbc.update("UPDATE analysis_run SET status='TOOL_RUNNING' WHERE id=?",bytes());
        assertThatThrownBy(()->adapter.call(session,"get_task_state",json.createObjectNode(),"late")).isInstanceOf(CopilotException.class);
        assertThatThrownBy(()->capabilities.issue(id,List.of("get_task_state"),"jwt")).isInstanceOf(CopilotException.class);
        assertThat(count()).isZero();
        jdbc.update("UPDATE analysis_checkpoint SET waiting=TRUE,wait_expires_at=? WHERE analysis_id=?",java.sql.Timestamp.from(now.plusSeconds(3600)),bytes());
        jdbc.update("UPDATE analysis_run SET status='WAITING_FOR_HUMAN' WHERE id=?",bytes());
        when(core.reviewTaskContext(42,"jwt")).thenReturn(facts("101"));
        assertThatThrownBy(()->capabilities.issue(id,List.of("get_task_state"),"jwt")).isInstanceOf(CopilotException.class)
            .extracting(e->((CopilotException)e).code()).isEqualTo(ErrorCode.CONTEXT_STALE);
    }
    @Test void issuanceRechecksRoleOwnershipAndCoreAvailabilityWithoutChangingTheRun(){
        when(core.currentUser("jwt")).thenReturn(new CurrentUser("reviewer",Set.of("ADMIN")));
        assertThatThrownBy(()->capabilities.issue(id,List.of("get_task_state"),"jwt")).isInstanceOf(CopilotException.class);
        when(core.currentUser("jwt")).thenReturn(new CurrentUser("other",Set.of("REVIEWER")));
        assertThatThrownBy(()->capabilities.issue(id,List.of("get_task_state"),"jwt")).isInstanceOf(CopilotException.class);
        when(core.currentUser("jwt")).thenReturn(new CurrentUser("reviewer",Set.of("REVIEWER")));
        when(core.reviewTaskContext(42,"jwt")).thenThrow(new CoreClientException(CoreClientException.Kind.UNAVAILABLE,"private",null));
        assertThatThrownBy(()->capabilities.issue(id,List.of("get_task_state"),"jwt")).isInstanceOf(CopilotException.class);
        assertThat(store.read(id).orElseThrow().revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_mcp_capability WHERE analysis_id=?",Integer.class,bytes())).isZero();
    }
    @Test void concurrentMcpCallsShareFourSlotsWithTheAgent() throws Exception {
        jdbc.update("INSERT INTO analysis_tool_execution(analysis_id,slot,step_key,tool_name,arguments_sha256,status) VALUES(?,1,'agent-read','get_task_state',REPEAT('a',64),'SUCCEEDED')",bytes());
        var session=capabilities.authorize(id,capabilities.issue(id,List.of("get_task_state"),"jwt").capability());
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(5);
        try{var jobs=new ArrayList<Future<Boolean>>();for(int n=0;n<5;n++){final int key=n;jobs.add(pool.submit(()->{start.await();try{adapter.call(session,"get_task_state",json.createObjectNode(),"call"+key);return true;}catch(CopilotException e){return false;}}));}
            start.countDown();int success=0;for(var job:jobs)if(job.get(20,TimeUnit.SECONDS))success++;assertThat(success).isEqualTo(3);assertThat(count()).isEqualTo(4);
        }finally{pool.shutdownNow();}
    }
    @Test void uncompletedMcpReadPreventsContinuationAndClaimRechecksRevocation(){
        var grant=capabilities.issue(id,List.of("get_task_state"),"jwt");var session=capabilities.authorize(id,grant.capability());
        jdbc.update("INSERT INTO analysis_tool_execution(analysis_id,slot,step_key,tool_name,arguments_sha256,status) VALUES(?,1,'mcp-inflight','get_task_state',REPEAT('a',64),'CLAIMED')",bytes());
        var resume=new ResumeAnalysisService(access,core,store,jdbc,tx,codec,Clock.fixed(now,ZoneOffset.UTC));
        assertThatThrownBy(()->resume.resume(id,1,ResumeAction.CONTINUE,"resume","jwt")).isInstanceOf(CopilotException.class);
        capabilities.revoke(id,"jwt");
        assertThatThrownBy(()->executor.execute(new ToolExecutionContext(id,"reviewer",HexFormat.of().formatHex(snapshot.sha256()),"frozen","mcp-late",session.revision(),session.capabilityHash()),new ToolInvocation("get_task_state",json.createObjectNode()))).isInstanceOf(CopilotException.class);
        assertThat(count()).isEqualTo(1);
    }
    ReviewTaskContext facts(String amount){return new ReviewTaskContext(new ReviewTaskContext.ReviewTask(42,0,"PENDING","LARGE_AMOUNT","REVIEW",now),new ReviewTaskContext.Transaction(2,3,new BigDecimal(amount),"CNY",now,"CSV_IMPORT"),new ReviewTaskContext.Reconciliation("SUSPICIOUS","NONE","REVIEW"),List.of(),new ReviewTaskContext.AccountSummary("ACTIVE","Primary"),now);}
    int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM analysis_tool_execution WHERE analysis_id=?",Integer.class,bytes());}
    byte[] bytes(){return JdbcAnalysisRunRepository.uuidBytes(id);}
}
