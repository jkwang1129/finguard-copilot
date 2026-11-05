package com.finguard.copilot.agent.budget;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.governance.*;
import com.finguard.copilot.model.port.ModelUsage;
import com.finguard.copilot.usage.ModelCostCalculator;
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
class AgentCallLedgerIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10")
        .withCommand("--log-bin-trust-function-creators=1");
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    AgentCallLedger ledger;
    UUID id;
    @BeforeEach void init(){
        ledger=new AgentCallLedger(jdbc,tx,Clock.systemUTC());id=UUID.randomUUID();
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED',?,42,0,?,?)",
            bytes(),id.toString(),id.toString(),new byte[32]);
        jdbc.update("INSERT INTO agent_run_policy(analysis_id,workflow_version,snapshot_sha256,index_version) VALUES(?,'agent-v1',?,'frozen')",bytes(),new byte[32]);
    }
    PreparedAgentCall prepared(String ceiling){
        return new PreparedAgentCall("deepseek","model-v1","prompt-v1","a".repeat(64),"frozen","b".repeat(64),
            1000,1000,new ModelCostCalculator.PriceTable("agent-price-v1","CNY",new BigDecimal("2"),new BigDecimal("8")),
            new BigDecimal("0.02"),new BigDecimal(ceiling),new BigDecimal("100"),new BigDecimal("100"),
            new BigDecimal("100"),Instant.now().plusSeconds(3600),false);
    }
    ModelUsage usage(){return new ModelUsage(100,100,Duration.ofMillis(10));}
    @Test void frozenGraphCannotClaimModelWithoutItsCurrentExecutionFence(){
        jdbc.update("UPDATE agent_run_policy SET runtime_enabled=TRUE WHERE analysis_id=?",bytes());
        assertThatThrownBy(()->ledger.acquire(id,1,prepared("0.03"))).hasMessageContaining("lease");
        assertThat(ledger.totals(id).calls()).isZero();
    }
    @Test void takeoverRevisionAndExpiryAreCheckedBeforeModelClaim(){
        jdbc.update("UPDATE agent_run_policy SET runtime_enabled=TRUE WHERE analysis_id=?",bytes());
        jdbc.update("INSERT INTO analysis_checkpoint(analysis_id,schema_version,workflow_version,revision,next_node,snapshot_sha256,index_version,owner_token,lease_until) VALUES(?,1,'agent-v1',7,'PLAN',?,'frozen','new-owner',?)",bytes(),new byte[32],java.sql.Timestamp.from(Instant.now().plusSeconds(120)));
        for(var fence:List.of(new com.finguard.copilot.agent.runtime.AgentExecutionFence("old-owner",7),new com.finguard.copilot.agent.runtime.AgentExecutionFence("new-owner",6)))
            assertThatThrownBy(()->ledger.acquire(id,1,prepared("0.03"),fence)).hasMessageContaining("lease");
        assertThat(ledger.totals(id).calls()).isZero();
        var fence=new com.finguard.copilot.agent.runtime.AgentExecutionFence("new-owner",7);
        jdbc.update("UPDATE analysis_checkpoint SET lease_until=? WHERE analysis_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),bytes());
        assertThatThrownBy(()->ledger.acquire(id,1,prepared("0.03"),fence)).hasMessageContaining("lease");
        assertThat(ledger.totals(id).calls()).isZero();
        jdbc.update("UPDATE analysis_checkpoint SET lease_until=? WHERE analysis_id=?",java.sql.Timestamp.from(Instant.now().plusSeconds(120)),bytes());
        ledger.acquire(id,1,prepared("0.03"),fence);assertThat(ledger.totals(id).calls()).isEqualTo(1);
    }
    @Test void keepsTwoIndependentCallsAndNeverOverwritesUsage(){
        var first=ledger.acquire(id,1,prepared("0.03"));
        ledger.recordOutcome(first,AgentCallOutcome.success(usage()));
        var second=ledger.acquire(id,2,prepared("0.03"));
        ledger.recordOutcome(second,AgentCallOutcome.success(usage()));
        ledger.recordOutcome(second,AgentCallOutcome.success(usage()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE analysis_id=?",Integer.class,bytes())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(settled_cny) FROM agent_model_call WHERE analysis_id=?",BigDecimal.class,bytes())).isEqualByComparingTo("0.002");
        assertThat(jdbc.queryForObject("SELECT SUM(held_cny) FROM model_budget_account WHERE scope_key=?",BigDecimal.class,"agent-analysis:"+id)).isEqualByComparingTo("0");
        assertThatThrownBy(()->ledger.acquire(id,3,prepared("0.03"))).hasMessageContaining("ordinal");
        assertThatThrownBy(()->ledger.acquire(id,2,prepared("0.03"))).hasMessageContaining("claimed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_call_reservation WHERE analysis_id=?",Integer.class,bytes())).isZero();
    }
    @Test void aggregateCeilingDoesNotResetForCallTwo(){
        var first=ledger.acquire(id,1,prepared("0.0105"));
        ledger.recordOutcome(first,AgentCallOutcome.success(usage()));
        assertThatThrownBy(()->ledger.acquire(id,2,prepared("0.0105"))).hasMessageContaining("budget");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE analysis_id=?",Integer.class,bytes())).isEqualTo(1);
    }
    @Test void unknownCostNeverReleasesOrAdmitsNextCall(){
        var first=ledger.acquire(id,1,prepared("0.03"));
        ledger.recordOutcome(first,AgentCallOutcome.unknown());
        assertThatThrownBy(()->ledger.acquire(id,2,prepared("0.03"))).hasMessageContaining("previous");
        assertThatThrownBy(()->ledger.recordOutcome(first,AgentCallOutcome.notSent())).hasMessageContaining("outcome");
        var totals=ledger.totals(id);
        assertThat(totals.heldCny()).isEqualByComparingTo("0.01");
        assertThat(totals.estimatedCost()).isNull();
        assertThat(totals.accountingStatus()).isEqualTo("UNKNOWN_HELD");
    }
    @Test void knownNotSentReleasesMoneyButCannotReopenOrdinal(){
        var first=ledger.acquire(id,1,prepared("0.03"));ledger.recordOutcome(first,AgentCallOutcome.notSent());
        assertThat(ledger.totals(id).heldCny()).isEqualByComparingTo("0");
        assertThat(ledger.totals(id).estimatedCost()).isNull();
        assertThatThrownBy(()->ledger.acquire(id,1,prepared("0.03"))).hasMessageContaining("claimed");
        assertThatThrownBy(()->ledger.acquire(id,2,prepared("0.03"))).hasMessageContaining("previous");
    }
    @Test void policyCannotChangeBetweenCalls(){
        var first=ledger.acquire(id,1,prepared("0.03"));ledger.recordOutcome(first,AgentCallOutcome.success(usage()));
        assertThatThrownBy(()->ledger.acquire(id,2,prepared("0.04"))).hasMessageContaining("policy");
    }
    @Test void legacyAndTerminalRunsCannotAcquireAgentCalls(){
        jdbc.update("DELETE FROM agent_run_policy WHERE analysis_id=?",bytes());
        assertThatThrownBy(()->ledger.acquire(id,1,prepared("0.03")));
        jdbc.update("INSERT INTO agent_run_policy(analysis_id,workflow_version,snapshot_sha256,index_version) VALUES(?,'agent-v1',?,'frozen')",bytes(),new byte[32]);
        jdbc.update("UPDATE analysis_run SET status='FAILED',failure_code='INTERNAL_ERROR' WHERE id=?",bytes());
        assertThatThrownBy(()->ledger.acquire(id,1,prepared("0.03")));
    }
    @Test void twoWorkersHaveOnlyOneCallClaim() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);var call=prepared("0.03");
        try {
            Callable<Boolean> action=()->{start.await();try{ledger.acquire(id,1,call);return true;}catch(com.finguard.copilot.common.error.CopilotException expected){return false;}};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {pool.shutdownNow();}
    }
    @Test void uncertainSettlementTransactionKeepsTheCommittedClaim(){
        var first=ledger.acquire(id,1,prepared("0.03"));
        jdbc.execute("CREATE TRIGGER fail_agent_event BEFORE INSERT ON agent_model_budget_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture'");
        try {assertThatThrownBy(()->ledger.recordOutcome(first,AgentCallOutcome.success(usage())));}
        finally {jdbc.execute("DROP TRIGGER fail_agent_event");}
        assertThatThrownBy(()->ledger.acquire(id,1,prepared("0.03"))).hasMessageContaining("claimed");
        assertThat(ledger.totals(id).heldCny()).isEqualByComparingTo("0.01");
    }
    @Test void redisFailureCreatesNoClaimAndExpiredLeaseIsKnownNotSent(){
        var permits=mock(RedisProviderPermits.class);
        var admission=new AgentCallAdmission(ledger,permits,Duration.ofSeconds(5));
        when(permits.acquire(anyString(),any())).thenThrow(RedisProviderPermits.capacity());
        assertThatThrownBy(()->admission.acquire(id,1,prepared("0.03")));
        assertThat(ledger.totals(id).calls()).isZero();
        reset(permits);var lease=new RedisProviderPermits.Lease("test");
        when(permits.acquire(anyString(),any())).thenReturn(lease);when(permits.valid(any(),any())).thenReturn(false);
        assertThatThrownBy(()->admission.acquire(id,1,prepared("0.03")));
        assertThat(ledger.totals(id).accountingStatus()).isEqualTo("RELEASED_NOT_SENT");
        verify(permits).release(lease);
    }
    @Test void failedProviderRequestCannotBeRetriedAsCallTwo(){
        var first=ledger.acquire(id,1,prepared("0.03"));
        ledger.recordOutcome(first,AgentCallOutcome.failure(usage()));
        assertThatThrownBy(()->ledger.acquire(id,2,prepared("0.03"))).hasMessageContaining("previous");
    }
    @Test void captureOnlyRunStillShowsItsLegacyAccountingUntilAgentCallsExist(){
        jdbc.update("INSERT INTO model_usage_ledger(analysis_id,provider,model_configuration_version,prompt_version,input_tokens,output_tokens,latency_ms,estimated_cost,currency,price_table_version,outcome) VALUES(?,'deepseek','legacy','legacy',100,10,1,0.001,'CNY','legacy','SUCCESS')",bytes());
        var core=mock(com.finguard.copilot.coreclient.port.CoreClient.class);
        when(core.currentUser("token")).thenReturn(new com.finguard.copilot.coreclient.port.CurrentUser(id.toString(),Set.of("REVIEWER")));
        var runs=new JdbcAnalysisRunRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc));
        var access=new com.finguard.copilot.analysis.application.GetAnalysisService(core,runs,new com.fasterxml.jackson.databind.ObjectMapper());
        var page=new com.finguard.copilot.analysis.application.AnalysisPageService(access,jdbc,new com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator());
        page.setAgentLedger(ledger);
        assertThat(page.view(id,"token").cost().accountingStatus()).isEqualTo("LEGACY_ESTIMATE");
    }
    byte[] bytes(){return JdbcAnalysisRunRepository.uuidBytes(id);}
}
