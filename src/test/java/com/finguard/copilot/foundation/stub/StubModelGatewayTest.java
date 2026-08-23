package com.finguard.copilot.foundation.stub;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.finguard.copilot.model.port.ModelRequest;
import com.finguard.copilot.model.port.ModelResponse;

import static org.assertj.core.api.Assertions.assertThat;

class StubModelGatewayTest {

    @Test
    void returnsDeterministicNonAdvisoryResponse() {
        StubModelGateway gateway = new StubModelGateway();
        ModelRequest request = new ModelRequest("analysis-42", "summarize", "facts");

        ModelResponse first = gateway.complete(request);
        ModelResponse second = gateway.complete(request);
        ModelResponse anotherRequest = gateway.complete(
                new ModelRequest("analysis-43", "summarize", "facts")
        );

        assertThat(first).isEqualTo(second);
        assertThat(anotherRequest.content()).isNotEqualTo(first.content());
        assertThat(first.modelId()).isEqualTo("stub-deterministic-v1");
        assertThat(first.content()).isEqualTo("stub:analysis-42");
        assertThat(first.inputTokens()).isZero();
        assertThat(first.outputTokens()).isZero();
        assertThat(List.of(first, second, anotherRequest))
                .extracting(ModelResponse::content)
                .allSatisfy(content -> assertThat(content)
                        .doesNotContain("CONFIRMED", "IGNORED"));
    }
}
