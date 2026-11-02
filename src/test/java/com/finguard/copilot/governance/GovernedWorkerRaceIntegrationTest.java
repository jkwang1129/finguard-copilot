package com.finguard.copilot.governance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.application.*;
import com.finguard.copilot.analysis.domain.*;
import com.finguard.copilot.analysis.persistence.*;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.dispatch.*;
import com.finguard.copilot.guardrail.*;
import com.finguard.copilot.model.port.ModelUsage;
import com.finguard.copilot.usage.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessagePostProcessor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"COPILOT_DB_URL=jdbc:mysql://unused/test","COPILOT_DB_USERNAME=test-only","COPILOT_DB_PASSWORD="})
@Testcontainers
class GovernedWorkerRaceIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10");
    @Autowired JdbcTemplate jdbc;@Autowired TransactionTemplate tx;@Autowired AnalysisRunRepository runs;@Autowired CopilotProperties properties;
    final Instant at=Instant.parse("2026-11-02T06:00:00Z");
    final Clock clock=Clock.fixed(at,ZoneOffset.UTC);final SnapshotCodec codec=new SnapshotCodec();
    BudgetReservations budgets;ModelCallReservations claims;ModelUsageRecorder recorder;UUID id;
    @BeforeEach void init() {
        for(String table:List.of("model_budget_event","model_budget_allocation","model_budget_reservation","model_budget_account","model_call_reservation",
                "model_run_manifest","model_versioned_artifact","model_usage_ledger","analysis_message_receipt","analysis_result","analysis_input_snapshot","analysis_run")) jdbc.update("DELETE FROM "+table);
        claims=new ModelCallReservations(jdbc);budgets=new BudgetReservations(jdbc,tx,claims,clock);
        recorder=new ModelUsageRecorder(jdbc,tx,new ModelCostCalculator.PriceTable("ignored","CNY",BigDecimal.ONE,BigDecimal.ONE),clock,null,budgets);
        id=GovernanceDatabaseFixtures.seed(jdbc,tx,"reviewer",codec.capture(facts()));
    }
    ReviewTaskContext facts() {
        return new ReviewTaskContext(new ReviewTaskContext.ReviewTask(1,0,"OPEN","RULE_TEST","TEST",at),
                new ReviewTaskContext.Transaction(11,42,new BigDecimal("12.50"),"CNY",at,"BANK"),
                new ReviewTaskContext.Reconciliation("MATCHED","NONE","TEST"),List.of(),new ReviewTaskContext.AccountSummary("ACTIVE","masked"),at);
    }
    AnalysisExecution result(boolean valid) {
        var result=new AnalysisResult(1,id,"summary","explanation",List.of(new AnalysisResult.BusinessEvidence(valid?11:99,42,new BigDecimal("12.50"),"CNY",
                "RULE_TEST","TEST",List.of("transaction.amount"))),List.of(),AnalysisRecommendation.INSUFFICIENT_EVIDENCE,"insufficient",List.of("policy"),List.of("test"));
        return new AnalysisExecution(result,"deepseek","model-v1","prompt-v1",new ModelUsage(100,10,Duration.ofMillis(1)));
    }
    abstract class Governed implements AnalysisOrchestrator {
        @Override public boolean requiresModelCall(){return true;}
        @Override public boolean managesModelReservation(){return true;}
        @Override public AnalysisResult analyze(UUID id,FactSnapshot snapshot){return execute(id,snapshot).result();}
        @Override public void modelCallOutcomeUnknown(UUID id){budgets.markUnknown(id,"TEST_UNKNOWN");}
    }
    AnalysisWorker worker(AnalysisOrchestrator orchestrator) {
        RabbitTemplate rabbit=mock(RabbitTemplate.class);
        doAnswer(inv->{inv.<CorrelationData>getArgument(4).getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
                .when(rabbit).convertAndSend(anyString(),anyString(),any(AnalysisRequestedMessage.class),any(MessagePostProcessor.class),any(CorrelationData.class));
        return new AnalysisWorker(runs,codec,orchestrator,new AnalysisOutputSchemaValidator(),new FactConsistencyValidator(),tx,rabbit,new ObjectMapper(),properties,clock,recorder,claims);
    }
    AnalysisRequestedMessage message(){return new AnalysisRequestedMessage(1,UUID.randomUUID(),id,at);}
    @Test void duplicateCapacityFailureCannotPoisonTheActiveOwnersSuccessfulCall() throws Exception {
        var both=new CountDownLatch(2);var claimed=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var attempts=new AtomicInteger();var httpCalls=new AtomicInteger();
        var orchestrator=new Governed(){@Override public AnalysisExecution execute(UUID id,FactSnapshot snapshot) {
            int number=attempts.incrementAndGet();both.countDown();
            try {
                if(!both.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
                if(number==1) {
                    if(!budgets.acquire(id)) throw new ExistingModelCallException();
                    httpCalls.incrementAndGet();claimed.countDown();finish.await(10,TimeUnit.SECONDS);return result(true);
                }
                claimed.await(10,TimeUnit.SECONDS);throw RedisProviderPermits.capacity();
            } catch(InterruptedException e){throw new IllegalStateException(e);}
        }};
        var pool=Executors.newFixedThreadPool(2);var a=worker(orchestrator);var message=message();
        try {
            var completion=new ExecutorCompletionService<AnalysisWorker.WorkerOutcome>(pool);
            completion.submit(()->a.process(message,1));completion.submit(()->a.process(message,1));
            if(!claimed.await(10,TimeUnit.SECONDS)) throw new AssertionError("claim absent");
            // Whichever thread acquired the claim blocks; the duplicate completes first.
            var duplicate=completion.poll(10,TimeUnit.SECONDS);
            assertThat(duplicate).isNotNull();assertThat(duplicate.get()).isEqualTo(AnalysisWorker.WorkerOutcome.RETRIED);
            assertThat(jdbc.queryForObject("SELECT status FROM analysis_run",String.class)).isEqualTo("QUEUED");
            finish.countDown();
            assertThat(completion.poll(10,TimeUnit.SECONDS).get()).isEqualTo(AnalysisWorker.WorkerOutcome.COMPLETED);
            assertThat(jdbc.queryForObject("SELECT status FROM model_budget_reservation",String.class)).isEqualTo("SETTLED");
            assertThat(httpCalls.get()).isEqualTo(1);
        } finally {finish.countDown();pool.shutdown();if(!pool.awaitTermination(15,TimeUnit.SECONDS)) pool.shutdownNow();}
    }
    @Test void outerFactRejectionAndLedgerFailureRetainUnknownBeforeReceipt() {
        var model=new Governed(){@Override public AnalysisExecution execute(UUID id,FactSnapshot snapshot){budgets.acquire(id);return result(false);}};
        jdbc.execute("ALTER TABLE model_usage_ledger ADD CONSTRAINT reject_test_ledger CHECK(input_tokens<0)");
        try {
            assertThat(worker(model).process(message(),1)).isEqualTo(AnalysisWorker.WorkerOutcome.FAILED_GUARDRAIL);
            assertThat(jdbc.queryForObject("SELECT status FROM model_budget_reservation",String.class)).isEqualTo("UNKNOWN_HELD");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt",Integer.class)).isEqualTo(1);
        } finally {jdbc.execute("ALTER TABLE model_usage_ledger DROP CHECK reject_test_ledger");}
    }
}
