package com.finguard.copilot.knowledge.port;

public interface EmbeddingGateway {

    EmbeddingVector embed(String text);
}
