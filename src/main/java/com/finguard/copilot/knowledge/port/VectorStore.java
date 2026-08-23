package com.finguard.copilot.knowledge.port;

import java.util.List;

public interface VectorStore {

    void upsert(VectorDocument document);

    List<VectorSearchResult> search(VectorSearchQuery query);

    void clear();
}
