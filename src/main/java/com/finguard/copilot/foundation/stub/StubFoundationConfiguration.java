package com.finguard.copilot.foundation.stub;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.VectorStore;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import com.finguard.copilot.model.port.ModelGateway;

@Configuration(proxyBeanMethods = false)
@Profile("stub")
@EnableConfigurationProperties(CopilotProperties.class)
public class StubFoundationConfiguration {

    @Bean
    ModelGateway modelGateway() {
        return new StubModelGateway();
    }

    @Bean
    EmbeddingGateway embeddingGateway(CopilotProperties properties) {
        return new FixedEmbeddingGateway(properties.embedding().dimensions());
    }

    @Bean
    VectorStore vectorStore() {
        return new InMemoryVectorStore();
    }

    @Bean
    @ConditionalOnBean(KnowledgeIndexRepository.class)
    KnowledgeRetrievalService knowledgeRetrievalService(
            KnowledgeIndexRepository repository,
            EmbeddingGateway embeddingGateway,
            VectorStore vectorStore,
            CopilotProperties properties
    ) {
        return new KnowledgeRetrievalService(repository, embeddingGateway, vectorStore,
                "fixed-v1", properties.retrieval().topK(), -1.0);
    }
}
