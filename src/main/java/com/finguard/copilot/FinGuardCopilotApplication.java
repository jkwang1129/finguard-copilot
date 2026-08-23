package com.finguard.copilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FinGuardCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinGuardCopilotApplication.class, args);
    }
}
