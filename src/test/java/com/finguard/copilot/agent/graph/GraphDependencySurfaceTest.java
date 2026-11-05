package com.finguard.copilot.agent.graph;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class GraphDependencySurfaceTest {
    @Test void routingLibraryDoesNotInstallAnUnusedMcpServerOrModelClient(){
        var loader=getClass().getClassLoader();
        assertThat(loader.getResource("io/modelcontextprotocol/server/McpServer.class")).isNull();
        assertThat(loader.getResource("org/springframework/ai/chat/client/ChatClient.class")).isNull();
        assertThat(loader.getResource("org/springframework/web/reactive/function/client/WebClient.class")).isNull();
    }
}
