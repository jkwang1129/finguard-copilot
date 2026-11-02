package com.finguard.copilot.dispatch;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finguard.copilot.analysis.application.ModelAnalysisOrchestrator;
import com.finguard.copilot.analysis.domain.*;
import com.finguard.copilot.analysis.persistence.*;
import com.finguard.copilot.config.*;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.guardrail.*;
import com.finguard.copilot.knowledge.domain.KnowledgeCitation;
import com.finguard.copilot.knowledge.retrieval.*;
import com.finguard.copilot.model.port.*;
import com.finguard.copilot.model.validation.StructuredAnalysisParser;
import com.finguard.copilot.usage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Evaluates the real Worker method and database side effects, not HTTP or broker delivery. */
@SpringBootTest(properties={"COPILOT_DB_URL=jdbc:mysql://unused/test","COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD=","finguard.copilot.dispatch.enabled=false"})
@Testcontainers
class Phase4WorkerComparisonIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10");
    private static final Instant NOW=Instant.parse("2026-11-02T00:00:00Z");
    private static final String SIMPLE="Synthetic USD review: amount >= 100.00 means CONFIRMED; otherwise IGNORED. No exemptions.";
    private static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    @Autowired AnalysisCreationStore creation;
    @Autowired AnalysisRunRepository runs;
    @Autowired SnapshotCodec codec;
    @Autowired TransactionTemplate transactions;
    @Autowired ModelUsageRecorder ledger;
    @Autowired ModelCallReservations reservations;
    @Autowired CopilotProperties properties;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temporary;

    @Test void capturesEveryFrozenCaseWithTerminalResultUsageReceiptAndReplay() throws Exception {
        Path dataset=Path.of(System.getProperty("p4.evaluation.dataset","tools/agent-evaluation/data/phase4-worker-v2.jsonl"));
        var freeze=JSON.readTree(Files.readString(Path.of(System.getProperty("p4.evaluation.freeze","docs/evaluations/phase4-worker-v2-freeze.json"))));
        String digest=hash(Files.readAllBytes(dataset));
        assertThat(digest).isEqualTo(freeze.get("dataset_sha256").asText());
        List<Map<String,Object>> records=new ArrayList<>();
        List<String> qualityFailures=new ArrayList<>();
        for (String line:Files.readAllLines(dataset,StandardCharsets.UTF_8)) {
            var row=JSON.readTree(line);
            String caseId=row.get("case_id").asText();
            UUID id=UUID.nameUUIDFromBytes(("phase4-worker-v2:"+caseId).getBytes(StandardCharsets.UTF_8));
            UUID event=UUID.nameUUIDFromBytes(("event:"+id).getBytes(StandardCharsets.UTF_8));
            var facts=facts(row);
            creation.create(new AnalysisCreationStore.NewAnalysis(id,"synthetic-reviewer",1,0,caseId,
                    MessageDigest.getInstance("SHA-256").digest(caseId.getBytes(StandardCharsets.UTF_8)),NOW),
                    codec.capture(facts),new AnalysisCreationStore.OutboxRecord(event,id,1,NOW,NOW),NOW.plusSeconds(86400));
            List<KnowledgeEvidence> evidence=new ArrayList<>();
            for (var doc:row.get("documents")) {
                String text=doc.get("content").asText();
                evidence.add(new KnowledgeEvidence(new KnowledgeCitation(doc.get("document_id").asText(),"v2",
                        doc.get("document_id").asText()+"-chunk","Document",hash(text.getBytes(StandardCharsets.UTF_8)),
                        1.0,"synthetic-worker-v2"),text));
            }
            var retrieval=mock(KnowledgeRetrievalService.class);
            when(retrieval.retrieve(anyString(),anyInt())).thenReturn(evidence);
            var candidate=new AnalysisResult(1,id,"Synthetic candidate","Synthetic policy explanation",
                    List.of(new AnalysisResult.BusinessEvidence(11,42,facts.transaction().amount(),facts.transaction().currency(),
                            facts.reviewTask().ruleCode(),"SYNTH",List.of("transaction.amount"))),
                    evidence.stream().map(item->new AnalysisResult.PolicyCitation(item.citation().documentId(),"v2","Document",item.excerpt())).toList(),
                    AnalysisRecommendation.valueOf(row.get("candidate").asText()),"Synthetic reasoning",List.of(),List.of("Fixture model"));
            String body=JSON.writeValueAsString(candidate);
            AtomicInteger calls=new AtomicInteger();
            ModelGateway gateway=request->{calls.incrementAndGet();return new ModelResponse("fixture",body,10,5);};
            var guard=new NumericThresholdGuard(List.of(new NumericThresholdGuard.Rule("synthetic-v2","P4_SINGLE","USD",
                    NumericThresholdGuard.Operator.GTE,new BigDecimal("100.00"),"SYNTH_SINGLE","v2","Document",
                    hash(SIMPLE.getBytes(StandardCharsets.UTF_8)))));
            var model=new CopilotProperties.Model("stub","fixture",URI.create("https://example.invalid"),
                    new SecretValue("test-only"),32000,2000,Duration.ofSeconds(10),"synthetic-v2");
            var orchestrator=new ModelAnalysisOrchestrator(gateway,new ContextAssembler(TokenBudget.phase3Defaults(),
                    TokenEstimator.conservative(),new PromptTemplate("review-analysis-v1")),new StructuredAnalysisParser(),
                    model,new CopilotProperties.Budget(32000,2000,1),retrieval,5,guard,reviewedPolicies());
            var worker=new AnalysisWorker(runs,codec,orchestrator,new AnalysisOutputSchemaValidator(),
                    new FactConsistencyValidator(),transactions,confirmedRabbit(),JSON,properties,
                    Clock.fixed(NOW,ZoneOffset.UTC),ledger,reservations);
            var message=new AnalysisRequestedMessage(1,event,id,NOW);
            var storedSnapshot=runs.findForWork(id).orElseThrow();
            boolean snapshotIntegrity;
            try {codec.decodeAndVerify(storedSnapshot.factsJson().getBytes(StandardCharsets.UTF_8),storedSnapshot.factsSha256());snapshotIntegrity=true;}
            catch(IllegalArgumentException mismatch) {snapshotIntegrity=false;}
            List<String> attempts=new ArrayList<>();
            AnalysisWorker.WorkerOutcome outcome=null;
            for(int attempt=1;attempt<=properties.dispatch().workerMaxAttempts();attempt++) {
                outcome=worker.process(message,attempt);
                attempts.add(outcome.name());
                var state=runs.findByIdAndReviewer(id,"synthetic-reviewer").orElseThrow().status();
                if(state==AnalysisStatus.SUCCEEDED || state==AnalysisStatus.FAILED) break;
            }
            var view=runs.findByIdAndReviewer(id,"synthetic-reviewer").orElseThrow();
            var replay=worker.process(message,1);
            byte[] bytes=JdbcAnalysisRunRepository.uuidBytes(id);
            int usageRows=jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_ledger WHERE analysis_id=?",Integer.class,bytes);
            int receipts=jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt WHERE analysis_id=?",Integer.class,bytes);
            assertThat(view.status()).as(caseId+" outcome="+outcome).isIn(AnalysisStatus.SUCCEEDED,AnalysisStatus.FAILED);
            assertThat(calls.get()).isEqualTo(snapshotIntegrity?1:0);
            assertThat(usageRows).isEqualTo(calls.get());
            assertThat(receipts).isEqualTo(1);
            assertThat(replay).isEqualTo(AnalysisWorker.WorkerOutcome.NOOP);
            JsonNode result=view.resultJson()==null ? null : JSON.readTree(view.resultJson());
            if (view.status()==AnalysisStatus.SUCCEEDED) assertThat(result).isNotNull();
            else assertThat(result).isNull();
            Map<String,Object> record=new LinkedHashMap<>();
            record.put("case_id",caseId);record.put("analysis_id",id.toString());
            record.put("status",view.status().name());record.put("result",result);
            record.put("recommendation",result==null?null:result.get("recommendation").asText());
            record.put("failure_code",view.failureCode());record.put("worker_outcome",outcome.name());
            record.put("replay_outcome",replay.name());record.put("model_calls",calls.get());
            record.put("worker_attempts",attempts);record.put("snapshot_integrity",snapshotIntegrity);
            record.put("snapshot_amount",JSON.readTree(storedSnapshot.factsJson()).get("transaction").get("amount").asText());
            record.put("ledger_rows",usageRows);record.put("receipt_rows",receipts);
            record.put("ledger_outcome",usageRows==0?null:jdbc.queryForObject("SELECT outcome FROM model_usage_ledger WHERE analysis_id=?",String.class,bytes));
            records.add(record);
            String expectedStatus="ACCEPTED".equals(row.get("expected_disposition").asText())?"SUCCEEDED":"FAILED";
            if(!view.status().name().equals(expectedStatus)
                    || !Objects.equals(record.get("recommendation"),row.get("expected_recommendation").isNull()?null:row.get("expected_recommendation").asText()))
                qualityFailures.add(caseId+":"+view.status()+":"+record.get("recommendation"));
        }
        assertThat(records).hasSize(freeze.get("count").asInt());
        Map<String,String> sources=new TreeMap<>();
        for (String path:List.of("dispatch/AnalysisWorker.java","analysis/application/ModelAnalysisOrchestrator.java",
                "guardrail/NumericThresholdGuard.java","guardrail/UntrustedInstructionGuard.java","model/validation/StructuredAnalysisParser.java",
                "guardrail/ReviewedPolicyGuard.java","analysis/application/AnalysisOrchestrator.java",
                "analysis/persistence/AnalysisCreationStore.java","analysis/persistence/JdbcAnalysisRunRepository.java",
                "guardrail/AnalysisOutputSchemaValidator.java","guardrail/FactConsistencyValidator.java","context/ContextAssembler.java")) {
            sources.put(path,hash(Files.readAllBytes(Path.of("src/main/java/com/finguard/copilot/"+path))));
        }
        Map<String,String> classes=new TreeMap<>();
        sources.put("migration/V8",hash(Files.readAllBytes(Path.of("src/main/resources/db/migration/V8__preserve_exact_analysis_json.sql"))));
        for (Class<?> type:List.of(AnalysisWorker.class,ModelAnalysisOrchestrator.class,NumericThresholdGuard.class,ReviewedPolicyGuard.class,UntrustedInstructionGuard.class)) {
            try(var stream=type.getResourceAsStream(type.getSimpleName()+".class")) {classes.put(type.getName(),hash(stream.readAllBytes()));}
        }
        Path output=Path.of(System.getProperty("p4.evaluation.output",temporary.resolve("java.json").toString()));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("manifest",
                Map.of("dataset_sha256",digest,"source_sha256",sources,"class_sha256",classes,
                       "scope","real Worker+MySQL; fixture gateway/retrieval; confirmed broker mock"),"cases",records))+"\n",
                StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        assertThat(qualityFailures).as("Every frozen quality failure remains in the artifact").isEmpty();
    }

    private ReviewTaskContext facts(JsonNode row) {
        return new ReviewTaskContext(new ReviewTaskContext.ReviewTask(1,0,"PENDING",row.get("rule_code").asText(),"SYNTH",NOW),
                new ReviewTaskContext.Transaction(11,42,new BigDecimal(row.get("amount").asText()),row.get("currency").asText(),NOW,"SYNTHETIC"),
                new ReviewTaskContext.Reconciliation("MATCHED","REFERENCE_ID","SYNTH"),List.of(),
                new ReviewTaskContext.AccountSummary(row.get("account_status").asText(),"synthetic"),NOW);
    }
    private RabbitTemplate confirmedRabbit() {
        var rabbit=mock(RabbitTemplate.class);
        doAnswer(invocation->{invocation.<CorrelationData>getArgument(4).getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
            .when(rabbit).convertAndSend(anyString(),anyString(),any(AnalysisRequestedMessage.class),any(MessagePostProcessor.class),any(CorrelationData.class));
        return rabbit;
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Trusted test policy configuration; never inferred from per-case documents, labels or candidate. */
    private ReviewedPolicyGuard reviewedPolicies() throws Exception {
        String all="Synthetic USD review: CONFIRMED if and only if amount >= 100.00 AND account_status is FROZEN; otherwise IGNORED.";
        String yes="Synthetic USD review for every amount and account status: return CONFIRMED.";
        String no="Synthetic USD review for every amount and account status: return IGNORED.";
        var allPolicy=new ReviewedPolicyGuard.Policy(binding("SYNTH_ALL",all),List.of(
                new ReviewedPolicyGuard.Condition(ReviewedPolicyGuard.Field.AMOUNT,ReviewedPolicyGuard.Operator.GTE,"100.00"),
                new ReviewedPolicyGuard.Condition(ReviewedPolicyGuard.Field.ACCOUNT_STATUS,ReviewedPolicyGuard.Operator.EQ,"FROZEN")),
                AnalysisRecommendation.CONFIRMED,AnalysisRecommendation.IGNORED);
        var yesPolicy=new ReviewedPolicyGuard.Policy(binding("SYNTH_ALWAYS_CONFIRM",yes),List.of(),AnalysisRecommendation.CONFIRMED,AnalysisRecommendation.CONFIRMED);
        var noPolicy=new ReviewedPolicyGuard.Policy(binding("SYNTH_ALWAYS_IGNORE",no),List.of(),AnalysisRecommendation.IGNORED,AnalysisRecommendation.IGNORED);
        return new ReviewedPolicyGuard(List.of(new ReviewedPolicyGuard.PolicySet("all-v1","P4_COMPLEX","USD",List.of(allPolicy)),
                new ReviewedPolicyGuard.PolicySet("conflict-v1","P4_COMPLEX","USD",List.of(yesPolicy,noPolicy))));
    }
    private ReviewedPolicyGuard.Binding binding(String document,String content) throws Exception {
        return new ReviewedPolicyGuard.Binding(document,"v2","Document",hash(content.getBytes(StandardCharsets.UTF_8)));
    }
}
