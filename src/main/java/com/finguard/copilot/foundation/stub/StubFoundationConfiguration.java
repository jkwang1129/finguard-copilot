package com.finguard.copilot.foundation.stub;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.VectorStore;
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
}
