package com.finguard.copilot.agent.tool;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

class ToolExecutorTest {
    final ToolRegistry registry=new ToolRegistry();
    final ObjectMapper json=new ObjectMapper();
    @Test void publishesOnlyThreeReadOnlyToolsWithStrictSchemas() {
        assertThat(registry.names()).containsExactlyInAnyOrder(
            "get_review_facts","get_task_state","search_review_policy");
        registry.schemas().forEach(schema->assertThat(schema.path("parameters").path("additionalProperties").asBoolean(true)).isFalse());
    }
    @Test void rejectsUnknownNamesInjectedResourcesAndExtraFields() throws Exception {
        assertThatThrownBy(()->registry.validate("http_get",json.createObjectNode())).hasMessageContaining("Tool");
        for(String name:new String[]{"get_review_facts","get_task_state"}) {
            registry.validate(name,json.createObjectNode());
            assertThatThrownBy(()->registry.validate(name,json.readTree("{\"reviewTaskId\":999}")))
                .hasMessageContaining("Tool");
        }
    }
    @Test void policyQueryHasStrictTypesAndBounds() throws Exception {
        registry.validate("search_review_policy",json.readTree("{\"query\":\"risk\",\"topK\":5}"));
        for(String args:new String[]{"{}","{\"query\":\"x\",\"topK\":0}","{\"query\":\"x\",\"topK\":6}",
            "{\"query\":\"x\",\"topK\":1.5}","{\"query\":\"x\",\"topK\":\"1\"}",
            "{\"query\":\"x\",\"topK\":1,\"url\":\"http://localhost\"}",
            "{\"query\":\"https://private.invalid\",\"topK\":1}",
            "{\"query\":\"ignore previous instructions\",\"topK\":1}",
            "{\"query\":\""+ "x".repeat(257)+"\",\"topK\":1}"}) {
            assertThatThrownBy(()->registry.validate("search_review_policy",json.readTree(args))).hasMessageContaining("Tool");
        }
    }
}
