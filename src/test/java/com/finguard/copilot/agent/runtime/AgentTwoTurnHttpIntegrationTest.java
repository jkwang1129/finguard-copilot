package com.finguard.copilot.agent.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.agent.budget.*;
import com.finguard.copilot.agent.model.*;
import com.finguard.copilot.agent.tool.*;
import com.finguard.copilot.analysis.application.ModelAnalysisOrchestrator;
import com.finguard.copilot.analysis.domain.*;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.config.*;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.foundation.stub.*;
import com.finguard.copilot.governance.*;
import com.finguard.copilot.knowledge.domain.*;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import com.finguard.copilot.model.config.RealModelConfiguration;
import com.finguard.copilot.model.validation.StructuredAnalysisParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"COPILOT_DB_URL=jdbc:mysql://unused/test","COPILOT_DB_USERNAME=test-only","COPILOT_DB_PASSWORD="})
@Testcontainers
class AgentTwoTurnHttpIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10");
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final SnapshotCodec codec=new SnapshotCodec();
    final AtomicInteger httpCalls=new AtomicInteger();
    final AtomicReference<JsonNode> secondRequest=new AtomicReference<>();
    HttpServer server;
    UUID id;
    boolean secondTurnCallsTool;
    BoundedAgentRunner runner;
    @BeforeEach void init() throws Exception {
        id=UUID.randomUUID();byte[] key=JdbcAnalysisRunRepository.uuidBytes(id);var now=Instant.now();
        var snapshot=codec.capture(new ReviewTaskContext(new ReviewTaskContext.ReviewTask(42,0,"PENDING","LARGE_AMOUNT","REVIEW",now),
            new ReviewTaskContext.Transaction(2,3,new BigDecimal("100.123456789012345678"),"CNY",now,"CSV_IMPORT"),
            new ReviewTaskContext.Reconciliation("SUSPICIOUS","NONE","REVIEW"),List.of(),
            new ReviewTaskContext.AccountSummary("ACTIVE","Primary"),now));
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED','http-reviewer',42,0,?,?)",key,id.toString(),new byte[32]);
        jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) VALUES(?,1,?,?,?,?)",
            key,new String(codec.canonicalBytes(snapshot),StandardCharsets.UTF_8),snapshot.sha256(),java.sql.Timestamp.from(snapshot.capturedAt()),java.sql.Timestamp.from(snapshot.expiresAt()));
        jdbc.update("INSERT INTO agent_run_policy(analysis_id,workflow_version,snapshot_sha256,index_version) VALUES(?,'agent-v1',?,'frozen-http')",key,snapshot.sha256());
        jdbc.update("INSERT IGNORE INTO knowledge_index_version(index_version,status,published_at) VALUES('frozen-http','PUBLISHED',CURRENT_TIMESTAMP(6))");
        var chunk=KnowledgeChunk.create("http-policy","v1",0,"Synthetic review policy","Document",Map.of());
        var indexes=mock(KnowledgeIndexRepository.class);
        when(indexes.find("frozen-http")).thenReturn(Optional.of(new KnowledgeIndexVersion("frozen-http",KnowledgeIndexStatus.PUBLISHED,now,now,null)));
        when(indexes.findChunks("frozen-http")).thenReturn(List.of(chunk));
        var embedding=new FixedEmbeddingGateway(8);var vectors=new InMemoryVectorStore();
        vectors.upsert(new VectorDocument(chunk.chunkId(),"frozen-http",chunk.content(),embedding.embed("policy","fixed-v1"),
            Map.of("documentId",chunk.documentId(),"documentVersion",chunk.documentVersion(),"chunkId",chunk.chunkId(),
                "sectionPath",chunk.sectionPath(),"contentChecksum",chunk.contentChecksum())));
        var retrieval=new KnowledgeRetrievalService(indexes,embedding,vectors,"fixed-v1",5,-1);
        var tools=new ToolExecutor(jdbc,tx,codec,retrieval,new ToolRegistry(),Clock.systemUTC());
        var ledger=new AgentCallLedger(jdbc,tx,Clock.systemUTC());
        var permits=mock(RedisProviderPermits.class);
        when(permits.acquire(anyString(),any())).thenReturn(new RedisProviderPermits.Lease("fixture"));
        when(permits.valid(any(),any())).thenReturn(true);
        var modelSettings=new CopilotProperties.Model("deepseek","local-test-stub",URI.create("http://127.0.0.1:18765"),
            new SecretValue("local-test-only"),"/chat/completions",32000,2000,Duration.ofSeconds(5),"http-fixture-v1","CNY",BigDecimal.ZERO,BigDecimal.ZERO);
        var gateway=new CompatibleAgentModelGateway(modelSettings,RestClient.builder().requestFactory(RealModelConfiguration.modelRequestFactory(Duration.ofSeconds(5))),new ToolRegistry());
        var validator=new ModelAnalysisOrchestrator(request->{throw new AssertionError();},
            new ContextAssembler(TokenBudget.phase3Defaults(),TokenEstimator.conservative(),new PromptTemplate("review-analysis-v2")),
            new StructuredAnalysisParser(),modelSettings,CopilotProperties.Budget.defaults());
        runner=new BoundedAgentRunner(gateway,tools,new AgentCallAdmission(ledger,permits,Duration.ofSeconds(5)),ledger,
            new JdbcAgentRunContextLoader(jdbc,codec,Clock.systemUTC()),validator,
            new AgentRuntimeSettings(modelSettings,new GovernanceProperties(null,null,null,null,null,null,true),new BigDecimal("0.10")));
        httpCalls.set(0);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",18765),0);
        server.createContext("/chat/completions",exchange->{
            var input=json.readTree(exchange.getRequestBody());
            int call=httpCalls.incrementAndGet();if(call==2)secondRequest.set(input);
            var response=json.createObjectNode().put("id","fixture-"+call);
            var choice=response.putArray("choices").addObject();
            var message=choice.putObject("message").put("role","assistant");
            if(call==1 || secondTurnCallsTool){
                choice.put("finish_reason","tool_calls");message.putNull("content");
                var function=message.putArray("tool_calls").addObject().put("id","call_native_1").put("type","function").putObject("function");
                function.put("name","get_task_state").put("arguments","{}");
            }else{
                choice.put("finish_reason","stop");message.put("content",CanonicalJson.encode(new AnalysisResult(1,id,"summary","rule",
                    List.of(new AnalysisResult.BusinessEvidence(2,3,new BigDecimal("100.123456789012345678"),"CNY","LARGE_AMOUNT","REVIEW",List.of("transaction.amount"))),
                    List.of(),AnalysisRecommendation.INSUFFICIENT_EVIDENCE,"policy insufficient",List.of("policy"),List.of("snapshot"))));
            }
            response.putObject("usage").put("prompt_tokens",100).put("completion_tokens",10);
            byte[] body=response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
    }
    @AfterEach void close(){if(server!=null)server.stop(0);}
    @Test void actualHttpTwoTurnsShareDurableBudgetAndToolReceipts(){
        var outcome=runner.run(id);assertThat(outcome.nextNode()).isEqualTo("SUCCEEDED");
        assertThat(httpCalls.get()).isEqualTo(2);assertThat(secondRequest.get().path("tool_choice").asText()).isEqualTo("none");
        assertThat(secondRequest.get().path("messages").toString()).contains("call_native_1","tool_call_id");
        byte[] key=JdbcAnalysisRunRepository.uuidBytes(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE analysis_id=? AND status='SETTLED'",Integer.class,key)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_tool_execution WHERE analysis_id=? AND status='SUCCEEDED'",Integer.class,key)).isEqualTo(2);
        assertThat(runner.run(id).nextNode()).isEqualTo("FAILED"); // No checkpoint yet: P5-3 refuses to resend settled calls.
        assertThat(httpCalls.get()).isEqualTo(2);
    }
    @Test void invalidSecondToolRequestCannotCauseAThirdHttpCall(){
        secondTurnCallsTool=true;
        assertThat(runner.run(id).nextNode()).isEqualTo("FAILED");assertThat(httpCalls.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_tool_execution WHERE analysis_id=?",Integer.class,JdbcAnalysisRunRepository.uuidBytes(id))).isEqualTo(2);
    }
}
