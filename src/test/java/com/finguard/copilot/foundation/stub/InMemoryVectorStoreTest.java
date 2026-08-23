package com.finguard.copilot.foundation.stub;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.finguard.copilot.knowledge.port.EmbeddingVector;
import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorSearchResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryVectorStoreTest {

    @Test
    void filtersByIndexRanksByCosineAndLimitsResults() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(document("b", "v1", 1.0, 0.0));
        store.upsert(document("a", "v1", 1.0, 0.0));
        store.upsert(document("c", "v1", 0.0, 1.0));
        store.upsert(document("hidden", "v2", 1.0, 0.0));

        List<VectorSearchResult> results = store.search(new VectorSearchQuery(
                "v1",
                new EmbeddingVector(List.of(1.0, 0.0)),
                2
        ));

        assertThat(results).extracting(result -> result.document().id())
                .containsExactly("a", "b");
        assertThat(results).extracting(VectorSearchResult::score)
                .containsExactly(1.0, 1.0);
        assertThatThrownBy(() -> results.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void upsertReplacesSameIndexAndIdAndClearRemovesEverything() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(document("doc", "v1", 1.0, 0.0));
        store.upsert(new VectorDocument(
                "doc",
                "v1",
                "replacement",
                new EmbeddingVector(List.of(0.0, 1.0)),
                Map.of()
        ));

        List<VectorSearchResult> beforeClear = store.search(new VectorSearchQuery(
                "v1",
                new EmbeddingVector(List.of(0.0, 1.0)),
                5
        ));
        assertThat(beforeClear).singleElement()
                .satisfies(result -> assertThat(result.document().content()).isEqualTo("replacement"));

        store.clear();

        assertThat(store.search(new VectorSearchQuery(
                "v1",
                new EmbeddingVector(List.of(0.0, 1.0)),
                5
        ))).isEmpty();
    }

    @Test
    void rejectsQueriesWithDifferentVectorDimensions() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(document("doc", "v1", 1.0, 0.0));

        assertThatThrownBy(() -> store.search(new VectorSearchQuery(
                "v1",
                new EmbeddingVector(List.of(1.0, 0.0, 0.0)),
                1
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }

    private VectorDocument document(String id, String indexVersion, double first, double second) {
        return new VectorDocument(
                id,
                indexVersion,
                "content-" + id,
                new EmbeddingVector(List.of(first, second)),
                Map.of("source", "test")
        );
    }
}
