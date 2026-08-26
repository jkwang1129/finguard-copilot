package com.finguard.copilot.coreclient.http;

import java.net.http.HttpClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.coreclient.port.CoreClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class CoreHttpConfiguration {

    @Bean
    CoreClient coreClient(
            RestClient.Builder restClientBuilder,
            CopilotProperties properties,
            ObjectMapper objectMapper
    ) {
        CopilotProperties.Core core = properties.core();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(core.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(core.readTimeout());

        RestClient restClient = restClientBuilder
                .baseUrl(core.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
        return new HttpCoreClient(restClient, strictResponseObjectMapper(objectMapper));
    }

    ObjectMapper strictResponseObjectMapper(ObjectMapper objectMapper) {
        ObjectMapper strictObjectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        strictObjectMapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        strictObjectMapper.coercionConfigFor(LogicalType.Float)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        strictObjectMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return strictObjectMapper;
    }
}
