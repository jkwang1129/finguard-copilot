package com.finguard.copilot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI finGuardCopilotOpenApi() {
        return new OpenAPI().info(new Info()
                .title("FinGuard Copilot API")
                .version("v1")
                .description("Read-only AI-assisted review analysis API"));
    }
}
