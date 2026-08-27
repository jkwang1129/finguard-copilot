package com.finguard.copilot.foundation.stub;

import org.junit.jupiter.api.Test;

import com.finguard.copilot.knowledge.port.EmbeddingVector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedEmbeddingGatewayTest {

    @Test
    void producesStableNormalizedVectorsWithConfiguredDimension() {
        FixedEmbeddingGateway gateway = new FixedEmbeddingGateway(8);

        EmbeddingVector first = gateway.embed("review task 42", "fixed-v1");
        EmbeddingVector second = gateway.embed("review task 42", "fixed-v1");
        EmbeddingVector different = gateway.embed("review task 43", "fixed-v1");
        EmbeddingVector differentVersion = gateway.embed("review task 42", "fixed-v2");

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(different);
        assertThat(first).isNotEqualTo(differentVersion);
        assertThat(first.dimension()).isEqualTo(8);
        assertThat(first.values()).allSatisfy(value -> assertThat(value).isFinite());
        double norm = Math.sqrt(first.values().stream().mapToDouble(value -> value * value).sum());
        assertThat(norm).isCloseTo(1.0, within(1.0e-12));
    }

    @Test
    void rejectsUnsupportedDimensionsAndBlankInput() {
        assertThatThrownBy(() -> new FixedEmbeddingGateway(3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedEmbeddingGateway(4097))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedEmbeddingGateway(8).embed(" ", "fixed-v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedEmbeddingGateway(8).embed("text", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
