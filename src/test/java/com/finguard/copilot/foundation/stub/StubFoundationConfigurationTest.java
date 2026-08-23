package com.finguard.copilot.foundation.stub;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.finguard.copilot.knowledge.port.EmbeddingGateway;
import com.finguard.copilot.knowledge.port.VectorStore;
import com.finguard.copilot.model.port.ModelGateway;

import static org.assertj.core.api.Assertions.assertThat;

class StubFoundationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StubFoundationConfiguration.class)
            .withPropertyValues(
                    "finguard.copilot.core.base-url=http://localhost:8080",
                    "finguard.copilot.embedding.dimensions=8",
                    "finguard.copilot.retrieval.top-k=5"
            );

    @Test
    void exposesFoundationPortsOnlyWhenStubProfileIsActive() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ModelGateway.class);
            assertThat(context).doesNotHaveBean(EmbeddingGateway.class);
            assertThat(context).doesNotHaveBean(VectorStore.class);
        });

        contextRunner.withPropertyValues("spring.profiles.active=stub").run(context -> {
            assertThat(context).hasSingleBean(ModelGateway.class);
            assertThat(context).hasSingleBean(EmbeddingGateway.class);
            assertThat(context).hasSingleBean(VectorStore.class);
            assertThat(context.getBean(EmbeddingGateway.class).embed("probe").dimension())
                    .isEqualTo(8);
        });
    }
}
