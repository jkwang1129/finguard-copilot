package com.finguard.copilot.agent.graph;
import static org.assertj.core.api.Assertions.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import java.util.Map;
import com.alibaba.cloud.ai.graph.*;
import org.junit.jupiter.api.Test;
class GraphCompatibilityTest {
    @Test void fixedBomGraphRunsBoundedConditionalLoopWithoutASecondCheckpointStore() throws Exception {
        var graph=new StateGraph();
        graph.addNode("retrieve",node_async(state->Map.of("attempt",state.<Integer>value("attempt").orElse(0)+1)));
        graph.addEdge(StateGraph.START,"retrieve");
        graph.addConditionalEdges("retrieve",edge_async(state->state.<Integer>value("attempt").orElse(0)<2?"retry":"done"),
            Map.of("retry","retrieve","done",StateGraph.END));
        var config=CompileConfig.builder().recursionLimit(12)
            .saverConfig(com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig.builder().build()).build();
        assertThat(config.checkpointSaver()).isEmpty();
        var result=graph.compile(config).invoke(Map.of("attempt",0)).orElseThrow();
        assertThat(result.<Integer>value("attempt")).contains(2);
    }
}
