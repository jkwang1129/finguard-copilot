package com.finguard.copilot.knowledge.retrieval;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import com.finguard.copilot.knowledge.domain.*;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.port.*;
import org.junit.jupiter.api.Test;
class PinnedKnowledgeRetrievalTest {
    @Test void doesNotSilentlyFollowNewPublicationAndRejectsRetiredFrozenVersion() {
        var repository=mock(KnowledgeIndexRepository.class);
        var embedding=mock(EmbeddingGateway.class);
        var vectors=mock(VectorStore.class);
        when(repository.find("old")).thenReturn(Optional.of(new KnowledgeIndexVersion("old",KnowledgeIndexStatus.RETIRED,Instant.now(),Instant.now(),null)));
        var service=new KnowledgeRetrievalService(repository,embedding,vectors,"embedding",5,-1);
        assertThatThrownBy(()->service.retrievePinned("old","risk",1)).isInstanceOf(KnowledgeRetrievalException.class);
        verify(repository,never()).findPublished();verifyNoInteractions(embedding,vectors);
    }
}
