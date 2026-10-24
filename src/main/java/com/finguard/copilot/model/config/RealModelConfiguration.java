package com.finguard.copilot.model.config;

import com.finguard.copilot.config.CopilotProperties;
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
@EnableConfigurationProperties(CopilotProperties.class)
public class RealModelConfiguration {

    @Bean
    CopilotProperties.Model realModel(CopilotProperties properties, Environment environment) {
        String profile = activeRealProfile(environment);
        properties.model().validateForRealProfile(profile);
        return properties.model();
    }

    @Bean
    ModelGateway modelGateway(CopilotProperties.Model model) {
        return new CompatibleChatModelGateway(model,
                RestClient.builder().requestFactory(modelRequestFactory(model.timeout())));
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
    EmbeddingGateway realEmbeddingGateway(CopilotProperties properties) {
        return new FixedEmbeddingGateway(properties.embedding().dimensions());
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
        return new KnowledgeRetrievalService(indexRepository, embeddingGateway, vectorStore,
                environment.getProperty("COPILOT_EMBEDDING_VERSION", "fixed-v1"), properties.retrieval().topK(),
                Double.parseDouble(environment.getProperty("COPILOT_RETRIEVAL_MINIMUM_SCORE", "-1.0")),
                documentRepository);
    }

    @Bean
    AnalysisOrchestrator modelAnalysisOrchestrator(
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            StructuredAnalysisParser parser,
            CopilotProperties properties,
            ObjectProvider<KnowledgeRetrievalService> retrieval
    ) {
        return new ModelAnalysisOrchestrator(modelGateway, contextAssembler, parser,
                properties.model(), properties.budget(), retrieval.getIfAvailable(),
                properties.retrieval().topK());
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
