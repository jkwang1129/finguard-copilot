package com.finguard.copilot.knowledge.port;

import java.util.List;

public interface VectorStore {

    void createIndex(String indexVersion, int dimension);

    void upsert(VectorDocument document);

    void upsert(String indexVersion, List<VectorDocument> documents);

    List<VectorSearchResult> search(VectorSearchQuery query);

    void deleteIndex(String indexVersion);

    void clear();
}
