package com.finguard.copilot.api.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"COPILOT_DB_URL=jdbc:mysql://unused/test","COPILOT_DB_USERNAME=test-only","COPILOT_DB_PASSWORD="})
@AutoConfigureMockMvc
@Testcontainers
class ReviewPageOpenApiIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.context.ApplicationContext application;

    @Test void applicationDoesNotExposeXsltViewsOrFragmentRenderingHandlers(){
        assertThat(application.getBeansOfType(org.springframework.web.servlet.view.xslt.XsltView.class)).isEmpty();
        assertThat(application.getBeansOfType(org.springframework.web.servlet.view.xslt.XsltViewResolver.class)).isEmpty();
        var mappings=application.getBean("requestMappingHandlerMapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var handlers=mappings.getHandlerMethods().entrySet().stream()
            .filter(entry->entry.getValue().getBeanType().getPackageName().startsWith("com.finguard.copilot")).toList();
        assertThat(handlers).isNotEmpty();
        for(var entry:handlers){
            var handler=entry.getValue();
            assertThat(org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(handler.getBeanType(),org.springframework.web.bind.annotation.RestController.class)).isTrue();
            assertThat(entry.getKey().getPatternValues()).doesNotContain("/**");
            Class<?> result=handler.getMethod().getReturnType();
            assertThat(org.springframework.web.servlet.View.class.isAssignableFrom(result)).isFalse();
            assertThat(org.springframework.web.servlet.ModelAndView.class.isAssignableFrom(result)).isFalse();
            assertThat(result.getName()).doesNotContain("FragmentsRendering");
        }
    }

    @Test void documentsExplicitNullsWithoutChangingRequiredExactStringTypesOrLegacyResponse() throws Exception {
        JsonNode api=json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(api.path("openapi").asText()).startsWith("3.1.");
        var schemas=api.path("components").path("schemas");
        var cost=schemas.path("Cost").path("properties");
        for(String field:new String[]{"estimatedCost","currency","heldCny","inputTokens","outputTokens"})
            assertNullable(cost.path(field),"type","string");
        var page=schemas.path("Page").path("properties");
        assertThat(page.path("reviewTaskId").path("type").asText()).isEqualTo("string");
        for(String field:new String[]{"failureCode","traceId","feedbackVerdict"}) assertNullable(page.path(field),"type","string");
        assertNullable(page.path("result"),"type","object");
        assertNullable(page.path("versions"),"$ref","#/components/schemas/Versions");
        assertThat(cost.path("accountingStatus").path("type").asText()).isEqualTo("string");
        assertThat(cost.path("callSlotClaimed").path("type").asText()).isEqualTo("boolean");
        assertThat(schemas.path("AnalysisResponse").path("properties").path("result").has("anyOf")).isFalse();
    }
    private static void assertNullable(JsonNode schema,String key,String expected) {
        assertThat(schema.path("anyOf").findValuesAsText("type")).contains("null");
        assertThat(schema.path("anyOf").findValuesAsText(key)).contains(expected);
    }
}
