package com.finguard.copilot.knowledge.retrieval;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import com.finguard.copilot.foundation.stub.FixedEmbeddingGateway;
import com.finguard.copilot.knowledge.domain.*;
import com.finguard.copilot.knowledge.persistence.*;
import com.finguard.copilot.knowledge.port.*;
import org.junit.jupiter.api.Test;

class SingleSourceMarginRetrievalTest {
    final KnowledgeIndexRepository indexes=mock(KnowledgeIndexRepository.class);
    final KnowledgeDocumentRepository documents=mock(KnowledgeDocumentRepository.class);
    final VectorStore vectors=mock(VectorStore.class);
    final FixedEmbeddingGateway embedding=new FixedEmbeddingGateway(4);
    final KnowledgeChunk first=KnowledgeChunk.create("first","v1",0,"first content","Policy",Map.of());
    final KnowledgeChunk second=KnowledgeChunk.create("second","v1",0,"second content","Policy",Map.of());
    SingleSourceMarginRetrievalTest() {
        var publication=Optional.of(new KnowledgeIndexVersion("idx",KnowledgeIndexStatus.PUBLISHED,Instant.EPOCH,Instant.EPOCH,null));
        when(indexes.findPublished()).thenReturn(publication);when(indexes.find("idx")).thenReturn(publication);
        when(indexes.findChunks("idx")).thenReturn(List.of(first,second));
        when(documents.findChunks("first","v1")).thenReturn(List.of(first));
        when(documents.findChunks("second","v1")).thenReturn(List.of(second));
    }
    KnowledgeRetrievalService service(int topK,double floor,double margin) {
        try { return KnowledgeRetrievalService.class.getConstructor(KnowledgeIndexRepository.class,EmbeddingGateway.class,
            VectorStore.class,String.class,int.class,double.class,KnowledgeDocumentRepository.class,double.class)
            .newInstance(indexes,embedding,vectors,"e5",topK,floor,documents,margin);
        } catch(java.lang.reflect.InvocationTargetException e) {throw (RuntimeException)e.getCause();}
        catch(ReflectiveOperationException e) {throw new AssertionError("margin retrieval contract missing",e);}
    }
    VectorSearchResult result(KnowledgeChunk chunk,double score,String index,String content) {
        return new VectorSearchResult(new VectorDocument(chunk.chunkId(),index,content,embedding.embed("x","e5"),Map.of(
            "documentId",chunk.documentId(),"documentVersion",chunk.documentVersion(),"chunkId",chunk.chunkId(),
            "sectionPath",chunk.sectionPath(),"contentChecksum",chunk.contentChecksum())),score);
    }
    void candidates(double winner,double runner) {
        when(vectors.search(any())).thenReturn(List.of(result(first,winner,"idx",""),result(second,runner,"idx","")));
    }
    @Test void runnerBelowFloorStillControlsRawMarginAndFetchesTwoWithMaximumOne() {
        candidates(.875,.75);
        assertThat(service(1,.8,.2).retrieve("q",1)).isEmpty();
        verify(vectors).search(argThat(q->q.topK()==2));
    }
    @Test void acceptsInclusiveScoreAndGapBoundariesAndReturnsOnlyWinner() {
        candidates(.875,.75);
        assertThat(service(1,.875,.125).retrieve("q",1)).singleElement()
            .extracting(e->e.citation().documentId()).isEqualTo("first");
        assertThat(service(1,.9,.125).retrieve("q",1)).isEmpty();
    }
    @Test void nearTieMissingRunnerAndDuplicateSourceAbstain() {
        candidates(.9,.899);assertThat(service(1,.8,.01).retrieve("q",1)).isEmpty();
        when(vectors.search(any())).thenReturn(List.of(result(first,.9,"idx","")));
        assertThat(service(1,.8,.01).retrieve("q",1)).isEmpty();
        when(vectors.search(any())).thenReturn(List.of());assertThat(service(1,.8,.01).retrieve("q",1)).isEmpty();
        when(vectors.search(any())).thenReturn(List.of(result(first,.9,"idx",""),result(first,.5,"idx","")));
        assertThat(service(1,.8,.01).retrieve("q",1)).isEmpty();
    }
    @Test void sortsRawCandidatesBeforeChoosingWinner() {
        when(vectors.search(any())).thenReturn(List.of(result(second,.5,"idx",""),result(first,.9,"idx","")));
        assertThat(service(1,.8,.1).retrievePinned("idx","q",1).evidence()).singleElement()
            .extracting(e->e.citation().documentId()).isEqualTo("first");
    }
    @Test void refusesCorruptRunnerEvenBelowFloorAndWhenVectorCarriesContent() {
        candidates(.9,.2);when(documents.findChunks("second","v1")).thenReturn(List.of(
            new KnowledgeChunk(second.chunkId(),"second","v1",0,"corrupt",second.contentChecksum(),"Policy",Map.of())));
        assertThatThrownBy(()->service(1,.8,.1).retrieve("q",1)).isInstanceOf(KnowledgeRetrievalException.class);
        when(vectors.search(any())).thenReturn(List.of(result(first,.9,"idx",first.content()),result(second,.2,"idx",second.content())));
        assertThatThrownBy(()->service(1,.8,.1).retrieve("q",1)).isInstanceOf(KnowledgeRetrievalException.class);
    }
    @Test void refusesRunnerOutsideMembershipOrIndex() {
        candidates(.9,.2);when(indexes.findChunks("idx")).thenReturn(List.of(first));
        assertThatThrownBy(()->service(1,.8,.1).retrieve("q",1)).isInstanceOf(KnowledgeRetrievalException.class);
        when(indexes.findChunks("idx")).thenReturn(List.of(first,second));
        when(vectors.search(any())).thenReturn(List.of(result(first,.9,"idx",""),result(second,.2,"other","")));
        assertThatThrownBy(()->service(1,.8,.1).retrieve("q",1)).isInstanceOf(KnowledgeRetrievalException.class);
    }
    @Test void refusesPublicationWithdrawnOrChangedDuringSearch() {
        candidates(.9,.2);
        when(indexes.find("idx")).thenReturn(Optional.of(new KnowledgeIndexVersion("idx",KnowledgeIndexStatus.PUBLISHED,Instant.EPOCH,Instant.EPOCH,null)),Optional.empty());
        assertThatThrownBy(()->service(1,.8,.1).retrievePinned("idx","q",1)).isInstanceOf(KnowledgeRetrievalException.class);
    }
    @Test void validatesGapAndSingleSourceConfiguration() {
        for(double margin:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-.1,2.01})
            assertThatThrownBy(()->service(1,.8,margin)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service(2,.8,.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service(1,.8,.1).retrieve("q",2)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void zeroMarginKeepsLegacyMultiSourceBehavior() {
        candidates(.9,.85);
        assertThat(service(5,.8,0).retrieve("q",2)).hasSize(2);
        verify(vectors).search(argThat(q->q.topK()==2));
    }
    @Test void descriptorIsImmutableAndOnlyPresentForActiveProfile() throws Exception {
        var method=KnowledgeRetrievalService.class.getMethod("descriptor");
        assertThat((Map<?,?>)method.invoke(service(5,.8,0))).isEmpty();
        @SuppressWarnings("unchecked") var descriptor=(Map<String,Object>)method.invoke(service(1,.8,.1));
        assertThat(descriptor).containsEntry("mode","single-source-margin-v1").containsEntry("candidateTopK",2)
            .containsEntry("topK",1).containsEntry("minimumMargin",.1).containsEntry("minimumScore",.8);
        assertThatThrownBy(()->descriptor.put("mode","changed")).isInstanceOf(UnsupportedOperationException.class);
    }
}
