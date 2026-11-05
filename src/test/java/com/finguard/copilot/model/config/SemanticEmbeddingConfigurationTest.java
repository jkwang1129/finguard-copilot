package com.finguard.copilot.model.config;

import static org.assertj.core.api.Assertions.*;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.foundation.stub.FixedEmbeddingGateway;
import com.finguard.copilot.knowledge.embedding.LoopbackSemanticEmbeddingGateway;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SemanticEmbeddingConfigurationTest {
    @Test void marginProfileRequiresExplicitSemanticModeAndTopOneWhileDefaultRemainsLegacy() throws Exception {
        var config=new RealModelConfiguration();
        var indexes=org.mockito.Mockito.mock(com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository.class);
        var documents=org.mockito.Mockito.mock(com.finguard.copilot.knowledge.persistence.KnowledgeDocumentRepository.class);
        var vectors=org.mockito.Mockito.mock(com.finguard.copilot.knowledge.port.VectorStore.class);
        var embedding=new FixedEmbeddingGateway(8);
        var one=new CopilotProperties(null,new CopilotProperties.Embedding(8),new CopilotProperties.Retrieval(1));
        var gap=new MockEnvironment().withProperty("COPILOT_RETRIEVAL_MINIMUM_MARGIN","0.02");
        assertThatThrownBy(()->config.knowledgeRetrievalService(indexes,documents,embedding,vectors,one,gap))
            .isInstanceOf(IllegalArgumentException.class);
        gap.withProperty("COPILOT_EMBEDDING_MODE","semantic");
        assertThatThrownBy(()->config.knowledgeRetrievalService(indexes,documents,embedding,vectors,properties(8),gap))
            .isInstanceOf(IllegalArgumentException.class);
        var active=config.knowledgeRetrievalService(indexes,documents,embedding,vectors,one,gap);
        var descriptor=com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService.class.getMethod("descriptor");
        assertThat(((java.util.Map<?,?>)descriptor.invoke(active)).containsKey("minimumMargin")).isTrue();
        var legacy=config.knowledgeRetrievalService(indexes,documents,embedding,vectors,properties(8),new MockEnvironment());
        assertThat((java.util.Map<?,?>)descriptor.invoke(legacy)).isEmpty();
    }
    CopilotProperties properties(int dimensions){return new CopilotProperties(null,new CopilotProperties.Embedding(dimensions),new CopilotProperties.Retrieval(5));}
    MockEnvironment semantic(){return new MockEnvironment().withProperty("COPILOT_EMBEDDING_MODE","semantic")
        .withProperty("COPILOT_EMBEDDING_ENDPOINT","http://127.0.0.1:8766")
        .withProperty("COPILOT_EMBEDDING_VERSION","e5-small-int8-test")
        .withProperty("COPILOT_EMBEDDING_MANIFEST_SHA256","a".repeat(64));}
    @Test void fixedDefaultStaysOfflineAndSemanticNeedsExplicitOptIn(){
        var config=new RealModelConfiguration();
        assertThat(config.realEmbeddingGateway(properties(8),new MockEnvironment())).isInstanceOf(FixedEmbeddingGateway.class);
        assertThat(config.realEmbeddingGateway(properties(384),semantic())).isInstanceOf(LoopbackSemanticEmbeddingGateway.class);
    }
    @Test void missingSemanticIdentityAndWrongModeCannotSilentlySelectFixed(){
        var config=new RealModelConfiguration();
        assertThatThrownBy(()->config.realEmbeddingGateway(properties(384),new MockEnvironment().withProperty("COPILOT_EMBEDDING_MODE","semantic"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->config.realEmbeddingGateway(properties(384),new MockEnvironment().withProperty("COPILOT_EMBEDDING_MODE","typo"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->config.realEmbeddingGateway(properties(8),semantic())).isInstanceOf(IllegalArgumentException.class);
    }
}
