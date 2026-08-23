package com.finguard.copilot.foundation.stub;

import org.junit.jupiter.api.Test;

import com.finguard.copilot.knowledge.port.EmbeddingVector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedEmbeddingGatewayTest {

    @Test
    void producesStableNormalizedVectorsWithConfiguredDimension() {
        FixedEmbeddingGateway gateway = new FixedEmbeddingGateway(8);

        EmbeddingVector first = gateway.embed("review task 42");
        EmbeddingVector second = gateway.embed("review task 42");
        EmbeddingVector different = gateway.embed("review task 43");

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(different);
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
        assertThatThrownBy(() -> new FixedEmbeddingGateway(8).embed(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
