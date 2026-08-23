package com.finguard.copilot.foundation.stub;

import java.util.Objects;

import com.finguard.copilot.model.port.ModelGateway;
import com.finguard.copilot.model.port.ModelRequest;
import com.finguard.copilot.model.port.ModelResponse;

public final class StubModelGateway implements ModelGateway {

    private static final String MODEL_ID = "stub-deterministic-v1";

    @Override
    public ModelResponse complete(ModelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return new ModelResponse(MODEL_ID, "stub:" + request.requestId(), 0, 0);
    }
}
