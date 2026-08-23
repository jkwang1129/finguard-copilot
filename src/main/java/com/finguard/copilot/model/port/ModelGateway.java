package com.finguard.copilot.model.port;

public interface ModelGateway {

    ModelResponse complete(ModelRequest request);
}
