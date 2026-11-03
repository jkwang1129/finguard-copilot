package com.finguard.copilot.governance;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.model.port.ModelUsage;
import com.finguard.copilot.usage.ModelCallReservations;
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
class BudgetReservationsIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10");
    @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
    BudgetReservations budgets;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-02T06:00:00Z"),ZoneOffset.UTC);
    @BeforeEach void init() {
        jdbc.update("DELETE FROM model_usage_ledger");
        for(String table:List.of("model_budget_event","model_budget_allocation","model_budget_reservation","model_budget_account",
                "model_call_reservation","model_run_manifest","model_versioned_artifact","analysis_input_snapshot","analysis_run")) jdbc.update("DELETE FROM "+table);
        budgets=new BudgetReservations(jdbc,tx,new ModelCallReservations(jdbc),clock);
    }
    UUID seed(String reviewer) {
        UUID id=UUID.randomUUID();byte[] bytes=JdbcAnalysisRunRepository.uuidBytes(id);
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED',?,1,0,?,?)",bytes,reviewer,id.toString(),new byte[32]);
        jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) VALUES(?,1,'{}',?,CURRENT_TIMESTAMP(6),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY))",bytes,new byte[32]);
        var price=CanonicalJson.tree(Map.of("provider","deepseek","modelId","deepseek-flash","currency","CNY","inputPerMillion",2,"outputPerMillion",8));
        var limits=CanonicalJson.tree(Map.of("maxCallCny","0.1","maxReviewerDailyCny","0.10","maxProviderDailyCny","0.10","maxGlobalDailyCny","0.10"));
        ObjectNode manifest=(ObjectNode)CanonicalJson.tree(Map.of("reviewTaskId",1,"reviewTaskVersion",0,"snapshotSha256","0".repeat(64),
                "price",Map.of("version","price-v1","sha256",CanonicalJson.hash(price)),"budget",Map.of("version","budget-v1","sha256",CanonicalJson.hash(limits)),
                "model",Map.of("version","model-v1"),"prompt",Map.of("version","prompt-v1"),"inputUpperBound",22000,"outputCap",2000));
        manifest.put("reservedEstimateCny","0.06000000");
        new VersionedArtifactStore(jdbc,tx).freeze(id,manifest,List.of(new VersionedArtifactStore.Artifact("price","price-v1",price),
                new VersionedArtifactStore.Artifact("budget","budget-v1",limits)));
        return id;
    }
    String state(UUID id) {return jdbc.queryForObject("SELECT status FROM model_budget_reservation WHERE analysis_id=?",String.class,JdbcAnalysisRunRepository.uuidBytes(id));}
    @Test void reservesAtomicallyAndSettlesOnceUsingFrozenRates() {
        UUID id=seed("one");assertThat(budgets.acquire(id)).isTrue();assertThat(budgets.acquire(id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_call_reservation",Integer.class)).isEqualTo(1);
        var usage=new ModelUsage(2018,709,Duration.ofMillis(10));
        assertThat(budgets.settle(id,usage)).isTrue();assertThat(budgets.settle(id,usage)).isTrue();
        assertThat(state(id)).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT settled_cny FROM model_budget_account WHERE scope_key='global:2026-11-02'",BigDecimal.class)).isEqualByComparingTo("0.00970800");
        assertThat(jdbc.queryForObject("SELECT held_cny FROM model_budget_account WHERE scope_key='global:2026-11-02'",BigDecimal.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_budget_event WHERE event_type='SETTLED'",Integer.class)).isEqualTo(1);
    }
    @Test void ledgerMetricsReflectUnknownHeldReleasedAndVerifiedSettlementFromMysql() {
        var released=seed("released");budgets.acquire(released);budgets.releaseNotSent(released);
        var settled=seed("settled");budgets.acquire(settled);budgets.settle(settled,new ModelUsage(100,10,Duration.ofMillis(1)));
        var unknown=seed("unknown");budgets.acquire(unknown);budgets.markUnknown(unknown,"MODEL_CALL_OUTCOME_UNKNOWN");
        var meters=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var metrics=new com.finguard.copilot.observability.LedgerMetrics(jdbc,meters);
        try {
            metrics.refresh();
            assertThat(meters.get("copilot.ledger.reservations").tag("state","UNKNOWN_HELD").gauge().value()).isEqualTo(1);
            assertThat(meters.get("copilot.ledger.reservations").tag("state","RELEASED_NOT_SENT").gauge().value()).isEqualTo(1);
            assertThat(meters.get("copilot.ledger.reservations").tag("state","SETTLED").gauge().value()).isEqualTo(1);
            assertThat(meters.get("copilot.ledger.call.slots").gauge().value()).isEqualTo(3);
            assertThat(meters.get("copilot.ledger.refresh.success").gauge().value()).isEqualTo(1);
        } finally {metrics.close();}
    }
    @Test void concurrentAnalysesCannotOverdrawSharedBudgetAndLoserHasNoCallSlot() throws Exception {
        UUID first=seed("one"),second=seed("two");var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->reserve(gate,first));var b=pool.submit(()->reserve(gate,second));gate.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_call_reservation",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT held_cny FROM model_budget_account WHERE scope_key='global:2026-11-02'",BigDecimal.class)).isEqualByComparingTo("0.06");
        } finally {pool.shutdown();if(!pool.awaitTermination(15,TimeUnit.SECONDS)) pool.shutdownNow();}
    }
    boolean reserve(CountDownLatch gate,UUID id) throws Exception {
        gate.await();try {return budgets.acquire(id);} catch(com.finguard.copilot.common.error.CopilotException e) {return false;}
    }
    @Test void unknownUsageKeepsMoneyAndReleaseNotSentRetainsCallSlot() {
        UUID id=seed("one");budgets.acquire(id);
        assertThat(budgets.settle(id,new ModelUsage(0,0,Duration.ofSeconds(20)))).isFalse();
        assertThat(state(id)).isEqualTo("UNKNOWN_HELD");assertThat(budgets.acquire(id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT held_cny FROM model_budget_account WHERE scope_key='global:2026-11-02'",BigDecimal.class)).isEqualByComparingTo("0.06");
    }
    @Test void provenNotSentReleasesMoneyButCannotObtainAnotherCall() {
        UUID id=seed("one");budgets.acquire(id);budgets.releaseNotSent(id);
        assertThat(state(id)).isEqualTo("RELEASED_NOT_SENT");assertThat(budgets.acquire(id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT held_cny FROM model_budget_account WHERE scope_key='global:2026-11-02'",BigDecimal.class)).isZero();
    }
    @Test void outOfBoundUsageAndConflictingSettlementDoNotOverwriteCost() {
        UUID id=seed("one");budgets.acquire(id);
        assertThat(budgets.settle(id,new ModelUsage(99999,1,Duration.ZERO))).isFalse();
        assertThat(state(id)).isEqualTo("UNKNOWN_HELD");
        assertThat(budgets.settle(id,new ModelUsage(100,10,Duration.ZERO))).isFalse();
    }
    @Test void conflictingSettlementDoesNotOverwriteCost() {
        UUID id=seed("one");budgets.acquire(id);
        assertThat(budgets.settle(id,new ModelUsage(100,10,Duration.ZERO))).isTrue();
        assertThatThrownBy(()->budgets.settle(id,new ModelUsage(101,10,Duration.ZERO))).hasMessageContaining("settlement conflict");
    }
    @Test void closedAnalysisCannotAcquireANewCallClaim() {
        UUID id=seed("one");jdbc.update("UPDATE analysis_run SET status='FAILED',failure_code='TEST_CLOSED' WHERE id=?",JdbcAnalysisRunRepository.uuidBytes(id));
        assertThatThrownBy(()->budgets.acquire(id)).hasMessageContaining("closed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_call_reservation",Integer.class)).isZero();
    }
    @Test void terminalDecisionAndAdmissionSerializeOnTheAnalysisRow() throws Exception {
        UUID id=seed("one");var locked=new CountDownLatch(1);var close=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var terminal=pool.submit(()->tx.execute(status->{
                jdbc.queryForObject("SELECT status FROM analysis_run WHERE id=? FOR UPDATE",String.class,JdbcAnalysisRunRepository.uuidBytes(id));
                locked.countDown();
                try {close.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}
                jdbc.update("UPDATE analysis_run SET status='FAILED',failure_code='TEST_CLOSED' WHERE id=?",JdbcAnalysisRunRepository.uuidBytes(id));return null;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var admission=pool.submit(()->budgets.acquire(id));
            assertThatThrownBy(()->admission.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            close.countDown();terminal.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(()->admission.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(AnalysisAdmissionClosedException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_call_reservation",Integer.class)).isZero();
        } finally {close.countDown();pool.shutdown();if(!pool.awaitTermination(15,TimeUnit.SECONDS)) pool.shutdownNow();}
    }
    @Test void outOfBoundObservationRetainsExactDifferenceWithoutChargingOrDuplicating() {
        UUID id=seed("one");budgets.acquire(id);var observed=new ModelUsage(99999,1,Duration.ofMillis(1234));
        assertThat(budgets.settle(id,observed)).isFalse();assertThat(budgets.settle(id,observed)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_budget_event WHERE event_type='OBSERVED_USAGE'",Integer.class)).isEqualTo(1);
        String evidence=jdbc.queryForObject("SELECT details_json FROM model_budget_event WHERE event_type='OBSERVED_USAGE'",String.class);
        assertThat(evidence).contains("99999","22000","1234","0.14000600");
        assertThat(jdbc.queryForObject("SELECT held_cny FROM model_budget_account WHERE scope_key='global:2026-11-02'",BigDecimal.class)).isEqualByComparingTo("0.06");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_ledger",Integer.class)).isZero();
    }
    @Test void usageLedgerUsesFrozenPriceAndCommitsWithSettlement() {
        UUID id=seed("one");budgets.acquire(id);
        var livePrice=new com.finguard.copilot.usage.ModelCostCalculator.PriceTable("changed-live-price","USD",new BigDecimal("99"),new BigDecimal("99"));
        var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var recorder=new com.finguard.copilot.usage.ModelUsageRecorder(jdbc,tx,livePrice,clock,metrics,budgets);
        var usage=new ModelUsage(2018,709,Duration.ofMillis(10));
        recorder.record(id,"deepseek","model-v1","prompt-v1",usage,"SUCCESS");
        recorder.record(id,"deepseek","model-v1","prompt-v1",usage,"SUCCESS");
        assertThat(jdbc.queryForObject("SELECT estimated_cost FROM model_usage_ledger",BigDecimal.class)).isEqualByComparingTo("0.00970800");
        assertThat(jdbc.queryForObject("SELECT price_table_version FROM model_usage_ledger",String.class)).isEqualTo("price-v1");
        assertThat(jdbc.queryForObject("SELECT currency FROM model_usage_ledger",String.class)).isEqualTo("CNY");
        assertThat(metrics.get("copilot.model.usage.records").counter().count()).isEqualTo(1);
    }
    @Test void providerCorrelationIsPersistedWithoutChangingTheReservedBalance() {
        UUID id=seed("one");budgets.acquire(id);
        var evidence=new com.finguard.copilot.model.port.ProviderCallEvidence(id.toString(),"deepseek","a".repeat(64),"b".repeat(64),200,10L,5L,10);
        budgets.recordProviderEvidence(evidence);budgets.recordProviderEvidence(evidence);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_budget_event WHERE event_type='PROVIDER_RESPONSE'",Integer.class)).isEqualTo(1);
        assertThat(state(id)).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT held_cny FROM model_budget_account WHERE scope_key='global:2026-11-02'",BigDecimal.class)).isEqualByComparingTo("0.06");
    }
    @Test void unverifiedUsageCannotPublishSuccessfulAnalysisAccounting() {
        UUID id=seed("one");budgets.acquire(id);
        var price=new com.finguard.copilot.usage.ModelCostCalculator.PriceTable("irrelevant","CNY",BigDecimal.ONE,BigDecimal.ONE);
        var recorder=new com.finguard.copilot.usage.ModelUsageRecorder(jdbc,tx,price,clock,null,budgets);
        assertThatThrownBy(()->recorder.record(id,"deepseek","model-v1","prompt-v1",new ModelUsage(99999,1,Duration.ZERO),"SUCCESS"))
                .hasMessageContaining("reconciliation");
        assertThat(state(id)).isEqualTo("UNKNOWN_HELD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_ledger",Integer.class)).isZero();
    }
    @Test void supplierCorrectionIsAppendOnlyIdempotentAndStopsFurtherAdmissionOverBudget() {
        UUID id=seed("one");budgets.acquire(id);budgets.markUnknown(id,"TIMEOUT");
        budgets.reconcileConfirmedCost(id,"invoice-1",new BigDecimal("0.12"));
        budgets.reconcileConfirmedCost(id,"invoice-1",new BigDecimal("0.12"));
        assertThat(state(id)).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT settled_cny FROM model_budget_account WHERE scope_key='global:2026-11-02'",BigDecimal.class)).isEqualByComparingTo("0.12");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_budget_event WHERE event_type='RECONCILED'",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->budgets.reconcileConfirmedCost(id,"invoice-1",new BigDecimal("0.01"))).hasMessageContaining("reconciliation conflict");
        assertThatThrownBy(()->budgets.acquire(seed("two"))).hasMessageContaining("budget exhausted");
    }
    @Test void unknownCallDoesNotCreateZeroCostLedgerAndLedgerFailureRollsBackSettlement() {
        UUID id=seed("one");budgets.acquire(id);
        var price=new com.finguard.copilot.usage.ModelCostCalculator.PriceTable("irrelevant","CNY",BigDecimal.ONE,BigDecimal.ONE);
        var recorder=new com.finguard.copilot.usage.ModelUsageRecorder(jdbc,tx,price,clock,null,budgets);
        jdbc.execute("ALTER TABLE model_usage_ledger ADD CONSTRAINT reject_test_ledger CHECK (input_tokens < 0)");
        try {
            assertThatThrownBy(()->recorder.record(id,"deepseek","model-v1","prompt-v1",new ModelUsage(100,10,Duration.ZERO),"SUCCESS"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(state(id)).isEqualTo("RESERVED");
        } finally {jdbc.execute("ALTER TABLE model_usage_ledger DROP CHECK reject_test_ledger");}
        recorder.record(id,"deepseek","model-v1","prompt-v1",new ModelUsage(0,0,Duration.ofSeconds(20)),"TIMEOUT");
        assertThat(state(id)).isEqualTo("UNKNOWN_HELD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_ledger",Integer.class)).isZero();
    }
}
