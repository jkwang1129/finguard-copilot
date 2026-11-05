package com.finguard.copilot.model.config;

import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.config.NumericGuardProperties;
import com.finguard.copilot.config.ReviewedPolicyProperties;
import com.finguard.copilot.guardrail.ReviewedPolicyGuard;
import com.finguard.copilot.guardrail.NumericThresholdGuard;
import com.finguard.copilot.model.adapter.openai.CompatibleChatModelGateway;
import com.finguard.copilot.model.port.ModelGateway;
import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.analysis.application.ModelAnalysisOrchestrator;
import com.finguard.copilot.context.ContextAssembler;
import com.finguard.copilot.context.PromptTemplate;
import com.finguard.copilot.context.TokenBudget;
import com.finguard.copilot.context.TokenEstimator;
import com.finguard.copilot.model.validation.StructuredAnalysisParser;
import com.finguard.copilot.foundation.stub.FixedEmbeddingGateway;
import com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.VectorStore;
import com.finguard.copilot.knowledge.qdrant.QdrantClient;
import com.finguard.copilot.knowledge.qdrant.QdrantConfiguration;
import com.finguard.copilot.knowledge.qdrant.QdrantHttpClient;
import com.finguard.copilot.knowledge.qdrant.QdrantVectorStore;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile({"real-model", "real-qwen", "real-deepseek", "real-zhipu"})
@EnableConfigurationProperties({CopilotProperties.class, NumericGuardProperties.class, ReviewedPolicyProperties.class,
        com.finguard.copilot.governance.GovernanceProperties.class,com.finguard.copilot.governance.PermitProperties.class})
public class RealModelConfiguration {

    @Bean
    CopilotProperties.Model realModel(CopilotProperties properties, Environment environment) {
        String profile = activeRealProfile(environment);
        properties.model().validateForRealProfile(profile);
        boolean primaryUnavailable = environment.getProperty("COPILOT_REVIEW_PRIMARY_UNAVAILABLE", Boolean.class, false);
        boolean fixtureOrAgent = environment.getProperty("finguard.copilot.governance.fixture-mode", Boolean.class, false)
                || environment.getProperty("finguard.copilot.agent.enabled", Boolean.class, false);
        return com.finguard.copilot.governance.ReviewModelRoute.select(properties.model(), properties.prompt().version(),
                primaryUnavailable, fixtureOrAgent, new com.finguard.copilot.config.SecretValue(
                        environment.getProperty("COPILOT_REVIEW_BACKUP_API_KEY", "")));
    }

    @Bean
    ModelGateway modelGateway(CopilotProperties.Model model,ObjectProvider<com.finguard.copilot.governance.BudgetReservations> budgets,
            ObjectProvider<com.finguard.copilot.observability.AnalysisTelemetry> telemetry) {
        return new CompatibleChatModelGateway(model,
                RestClient.builder().requestFactory(modelRequestFactory(model.timeout())),value->budgets.getObject().recordProviderEvidence(value))
                .withTelemetry(telemetry.getIfAvailable(com.finguard.copilot.observability.AnalysisTelemetry::noop));
    }

    public static JdkClientHttpRequestFactory modelRequestFactory(Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return factory;
    }

    @Bean
    PromptTemplate promptTemplate(CopilotProperties properties) {
        return new PromptTemplate(properties.prompt().version());
    }

    @Bean
    ContextAssembler contextAssembler(CopilotProperties properties, PromptTemplate promptTemplate) {
        CopilotProperties.Budget configured = properties.budget();
        TokenBudget budget = new TokenBudget(configured.maxInputTokens(), configured.maxOutputTokens(),
                4_000, 8_000, 12_000, 2_000, 6_000);
        return new ContextAssembler(budget, TokenEstimator.conservative(), promptTemplate);
    }

    @Bean
    StructuredAnalysisParser structuredAnalysisParser() {
        return new StructuredAnalysisParser();
    }

    @Bean
    NumericThresholdGuard numericThresholdGuard(NumericGuardProperties properties) {
        return new NumericThresholdGuard(properties.rules());
    }

    @Bean
    ReviewedPolicyGuard reviewedPolicyGuard(ReviewedPolicyProperties properties) {
        return new ReviewedPolicyGuard(properties.sets());
    }

    @Bean
    EmbeddingGateway realEmbeddingGateway(CopilotProperties properties,Environment environment) {
        String mode=environment.getProperty("COPILOT_EMBEDDING_MODE","fixed");
        if("fixed".equals(mode))return new FixedEmbeddingGateway(properties.embedding().dimensions());
        if(!"semantic".equals(mode))throw new IllegalArgumentException("Unknown embedding mode");
        return new com.finguard.copilot.knowledge.embedding.LoopbackSemanticEmbeddingGateway(
            java.net.URI.create(environment.getRequiredProperty("COPILOT_EMBEDDING_ENDPOINT")),
            environment.getRequiredProperty("COPILOT_EMBEDDING_VERSION"),
            environment.getRequiredProperty("COPILOT_EMBEDDING_MANIFEST_SHA256"),properties.embedding().dimensions(),
            DurationStyle.detectAndParse(environment.getProperty("COPILOT_EMBEDDING_TIMEOUT","10s")));
    }

    @Bean
    QdrantConfiguration qdrantConfiguration(CopilotProperties properties, Environment environment) {
        return new QdrantConfiguration(
                java.net.URI.create(environment.getProperty("COPILOT_QDRANT_ENDPOINT",
                        "http://127.0.0.1:6333")),
                environment.getProperty("COPILOT_QDRANT_COLLECTION_PREFIX", "finguard-copilot"),
                DurationStyle.detectAndParse(environment.getProperty("COPILOT_QDRANT_CONNECT_TIMEOUT", "2s")),
                DurationStyle.detectAndParse(environment.getProperty("COPILOT_QDRANT_READ_TIMEOUT", "5s")),
                properties.embedding().dimensions());
    }

    @Bean
    QdrantClient qdrantClient(QdrantConfiguration configuration, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new QdrantHttpClient(configuration, objectMapper);
    }

    @Bean
    VectorStore qdrantVectorStore(QdrantClient client, QdrantConfiguration configuration) {
        return new QdrantVectorStore(client, configuration.collectionPrefix());
    }

    @Bean
    @ConditionalOnBean({KnowledgeIndexRepository.class, KnowledgeDocumentRepository.class})
    KnowledgeRetrievalService knowledgeRetrievalService(
            KnowledgeIndexRepository indexRepository,
            KnowledgeDocumentRepository documentRepository,
            EmbeddingGateway embeddingGateway,
            VectorStore vectorStore,
            CopilotProperties properties,
            Environment environment
    ) {
        double minimumMargin=Double.parseDouble(environment.getProperty("COPILOT_RETRIEVAL_MINIMUM_MARGIN", "0"));
        if(minimumMargin>0 && !"semantic".equals(environment.getProperty("COPILOT_EMBEDDING_MODE")))
            throw new IllegalArgumentException("margin retrieval requires explicit semantic embedding mode");
        return new KnowledgeRetrievalService(indexRepository, embeddingGateway, vectorStore,
                environment.getProperty("COPILOT_EMBEDDING_VERSION", "fixed-v1"), properties.retrieval().topK(),
                Double.parseDouble(environment.getProperty("COPILOT_RETRIEVAL_MINIMUM_SCORE", "-1.0")),
                documentRepository,minimumMargin);
    }

    @Bean
    com.finguard.copilot.governance.RedisProviderPermits redisProviderPermits(
            ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis,com.finguard.copilot.governance.PermitProperties permits,
            CopilotProperties.Model model) {
        return new com.finguard.copilot.governance.RedisProviderPermits(redis::getObject,permits.providerConcurrency(),permits.reviewerPerMinute(),
                com.finguard.copilot.governance.ReviewModelRoute.isQualifiedBackup(model) ? "zhipu" : "deepseek");
    }

    @Bean
    com.finguard.copilot.governance.CallAdmission callAdmission(ObjectProvider<com.finguard.copilot.governance.BudgetReservations> budgets,
            com.finguard.copilot.governance.RedisProviderPermits permits,CopilotProperties.Model model) {
        return new com.finguard.copilot.governance.CallAdmission(budgets::getObject,permits,model.timeout());
    }

    @Bean
    com.finguard.copilot.governance.ModelCallPreflight modelCallPreflight(
            ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbc,
            ObjectProvider<org.springframework.transaction.support.TransactionTemplate> transactions,
            ObjectProvider<KnowledgeIndexRepository> indexes,CopilotProperties properties,CopilotProperties.Model model,
            ObjectProvider<com.finguard.copilot.governance.BudgetReservations> budgets,
            com.finguard.copilot.governance.CallAdmission admission,
            NumericGuardProperties numeric,ReviewedPolicyProperties reviewed,
            com.finguard.copilot.governance.GovernanceProperties governance,Environment environment,EmbeddingGateway embedding,
            ObjectProvider<KnowledgeRetrievalService> retrieval) {
        return new com.finguard.copilot.governance.ModelCallPreflight() {
          @Override public boolean managesReservation() {return true;}
          @Override public void outcomeUnknown(java.util.UUID id) {budgets.getObject().markUnknown(id,"MODEL_CALL_OUTCOME_UNKNOWN");}
          @Override public void outcomeUnknown(java.util.UUID id,com.finguard.copilot.model.port.ModelUsage usage) {
              budgets.getObject().markUnknown(id,"MODEL_CALL_OUTCOME_UNKNOWN",usage);
          }
          @Override public void afterCall(java.util.UUID id) {admission.close(id);}
          @Override public com.finguard.copilot.model.port.ModelRequest prepare(java.util.UUID id,com.finguard.copilot.context.FactSnapshot snapshot,
                com.finguard.copilot.model.port.ModelRequest request,java.util.List<com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence> evidence,String indexVersion) {
            if(jdbc.getIfAvailable()==null || transactions.getIfAvailable()==null || indexes.getIfAvailable()==null || budgets.getIfAvailable()==null)
                throw com.finguard.copilot.governance.RunManifestService.rejected("manifest persistence dependencies unavailable");
            return new com.finguard.copilot.governance.RunManifestService(
                    new com.finguard.copilot.governance.VersionedArtifactStore(jdbc.getObject(),transactions.getObject()),
                    indexes.getObject(),properties,numeric,reviewed,environment.getProperty("COPILOT_EMBEDDING_VERSION","fixed-v1"),
                    governance,java.time.Clock.systemUTC(),admission,embedding,
                    retrieval.getIfAvailable()==null?java.util.Map.of():retrieval.getObject().descriptor(),model).prepare(id,snapshot,request,evidence,indexVersion);
          }
        };
    }

    @Bean
    AnalysisOrchestrator modelAnalysisOrchestrator(
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            StructuredAnalysisParser parser,
            CopilotProperties properties,
            CopilotProperties.Model model,
            NumericThresholdGuard numericThresholdGuard,
            ReviewedPolicyGuard reviewedPolicyGuard,
            com.finguard.copilot.governance.ModelCallPreflight preflight,
            ObjectProvider<KnowledgeRetrievalService> retrieval,
            ObjectProvider<com.finguard.copilot.observability.AnalysisTelemetry> telemetry
    ) {
        return new ModelAnalysisOrchestrator(modelGateway, contextAssembler, parser,
                model, properties.budget(), retrieval.getIfAvailable(),
                properties.retrieval().topK(), numericThresholdGuard, reviewedPolicyGuard,preflight)
                .withTelemetry(telemetry.getIfAvailable(com.finguard.copilot.observability.AnalysisTelemetry::noop));
    }

    private String activeRealProfile(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (profile.equals("real-qwen") || profile.equals("real-deepseek")
                    || profile.equals("real-zhipu") || profile.equals("real-model")) {
                return profile;
            }
        }
        return "real-model";
    }

}
