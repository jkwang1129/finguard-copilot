package com.finguard.copilot.knowledge.qdrant;

import java.util.List;

import com.finguard.copilot.knowledge.port.VectorDocument;
import com.finguard.copilot.knowledge.port.VectorSearchQuery;
import com.finguard.copilot.knowledge.port.VectorSearchResult;

public interface QdrantClient {

    void createCollection(String collectionName, int dimension);

    void upsert(String collectionName, List<VectorDocument> documents);

    List<VectorSearchResult> search(String collectionName, VectorSearchQuery query);

    void deleteCollection(String collectionName);
}
