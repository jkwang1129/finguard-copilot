package com.finguard.copilot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Set;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI finGuardCopilotOpenApi() {
        return new OpenAPI().info(new Info()
                .title("FinGuard Copilot API")
                .version("v1")
                .description("Read-only AI-assisted review analysis API"))
                .components(new io.swagger.v3.oas.models.Components()
                    .addSecuritySchemes("CoreReviewerBearer",new io.swagger.v3.oas.models.security.SecurityScheme()
                        .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                    .addSecuritySchemes("McpCapability",new io.swagger.v3.oas.models.security.SecurityScheme()
                        .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("short-lived random capability")));
    }

    /** The page emits explicit nulls; OpenAPI 3.1 must distinguish null from an absent property. */
    @Bean
    OpenApiCustomizer reviewPageNullability() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            var schemas = api.getComponents().getSchemas();
            nullableProperties(schemas.get("Cost"), "estimatedCost", "currency", "heldCny", "inputTokens", "outputTokens");
            nullableProperties(schemas.get("Page"), "failureCode", "result", "versions", "traceId", "feedbackVerdict", "agent");
            nullableProperties(schemas.get("Summary"),"waitingReason","waitExpiresAt");
            nullableProperties(schemas.get("CallTrace"),"estimatedCost","inputTokens","outputTokens");
            nullableProperties(schemas.get("ToolTrace"),"checksum");
            nullableProperties(schemas.get("Event"),"agentRevision","agentNode","toolNames");
        };
    }

    private static void nullableProperties(Schema<?> schema, String... names) {
        if (schema == null || schema.getProperties() == null) return;
        for (String name : names) {
            Schema<?> original = schema.getProperties().get(name);
            if (original == null || original.getTypes() != null && original.getTypes().contains("null")
                    || original.getAnyOf() != null && original.getAnyOf().stream().anyMatch(item -> "null".equals(item.getType())
                    || item.getTypes() != null && item.getTypes().contains("null"))) continue;
            var nullValue = new Schema<>();
            nullValue.setTypes(Set.of("null"));
            schema.getProperties().put(name, new ComposedSchema().addAnyOfItem(original).addAnyOfItem(nullValue));
        }
    }
}
