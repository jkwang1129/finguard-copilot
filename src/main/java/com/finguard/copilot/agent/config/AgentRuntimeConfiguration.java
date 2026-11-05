package com.finguard.copilot.agent.config;
import java.math.BigDecimal;
import java.time.Clock;
import com.finguard.copilot.agent.budget.*;
import com.finguard.copilot.agent.model.*;
import com.finguard.copilot.agent.runtime.*;
import com.finguard.copilot.agent.tool.*;
import com.finguard.copilot.analysis.application.*;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.governance.*;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import com.finguard.copilot.model.config.RealModelConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="finguard.copilot.agent",name="runtime-enabled",havingValue="true")
public class AgentRuntimeConfiguration {
    @Bean com.finguard.copilot.agent.graph.AgentFaultHooks agentFaultHooks(AgentRuntimeSettings settings,Environment env){
        return new com.finguard.copilot.agent.graph.AgentFaultHooks(env.getProperty("finguard.copilot.agent.fixture-fault-point"),
            settings.governance().fixtureMode(),DeepseekTextTokenBound.fixtureEndpoint(settings.model()),
            env.getProperty("finguard.copilot.agent.fixture-probes",Boolean.class,false));
    }
    @Bean
    public AgentRuntimeSettings runtimeSettings(CopilotProperties properties,GovernanceProperties governance,Environment env){
        if(!env.getProperty("finguard.copilot.agent.enabled",Boolean.class,false)) {
            throw new IllegalStateException("Agent runtime requires explicit capture configuration");
        }
        String ceiling=env.getProperty("finguard.copilot.agent.max-analysis-cny");
        if(ceiling==null || ceiling.isBlank()) throw new IllegalStateException("Explicit Agent analysis budget is required");
        return new AgentRuntimeSettings(properties.model(),governance,new BigDecimal(ceiling),env.getProperty("finguard.copilot.agent.native-bound-version"));
    }
    @Bean ToolRegistry agentToolRegistry(){return new ToolRegistry();}
    @Bean ToolExecutor agentToolExecutor(JdbcTemplate jdbc,TransactionTemplate tx,SnapshotCodec codec,
            KnowledgeRetrievalService retrieval,ToolRegistry registry,com.finguard.copilot.agent.graph.AgentFaultHooks faults,
            com.finguard.copilot.observability.AnalysisTelemetry telemetry){
        return new ToolExecutor(jdbc,tx,codec,retrieval,registry,Clock.systemUTC()).faults(faults).telemetry(telemetry);
    }
    @Bean AgentModelGateway agentModelGateway(AgentRuntimeSettings settings,ToolRegistry registry){
        return new CompatibleAgentModelGateway(settings.model(),
            RestClient.builder().requestFactory(RealModelConfiguration.modelRequestFactory(settings.model().timeout())),registry);
    }
    @Bean AgentRunContextLoader agentContextLoader(JdbcTemplate jdbc,SnapshotCodec codec){
        return new JdbcAgentRunContextLoader(jdbc,codec,Clock.systemUTC());
    }
    @Bean AgentCallAdmission agentCallAdmission(AgentCallLedger ledger,RedisProviderPermits permits,AgentRuntimeSettings settings){
        return new AgentCallAdmission(ledger,permits,settings.model().timeout());
    }
    @Bean BoundedAgentRunner boundedAgentRunner(AgentModelGateway model,ToolExecutor tools,AgentCallAdmission admission,
            AgentCallLedger ledger,AgentRunContextLoader contexts,AnalysisOrchestrator validation,AgentRuntimeSettings settings,
            com.finguard.copilot.observability.AnalysisTelemetry telemetry,com.finguard.copilot.agent.graph.AgentFaultHooks faults){
        if(!(validation instanceof ModelAnalysisOrchestrator validator)) throw new IllegalStateException("Agent runtime requires a configured model validation profile");
        return new BoundedAgentRunner(model,tools,admission,ledger,contexts,validator,settings).telemetry(telemetry).faults(faults);
    }
    @Bean com.finguard.copilot.agent.graph.ReviewAgentGraph reviewAgentGraph(BoundedAgentRunner runner,
            com.finguard.copilot.agent.checkpoint.CheckpointStore checkpoints,Environment env,
            com.finguard.copilot.observability.AnalysisTelemetry telemetry,com.finguard.copilot.agent.graph.AgentFaultHooks faults){
        return new com.finguard.copilot.agent.graph.ReviewAgentGraph(runner,checkpoints,
            env.getProperty("finguard.copilot.agent.human-gate",Boolean.class,false),telemetry).faults(faults);
    }
}
