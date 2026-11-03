package com.finguard.copilot.observability;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Base64;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.*;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
public class TelemetryConfiguration {
    @Bean(destroyMethod="shutdown")
    SdkTracerProvider copilotTracerProvider(Environment env,MeterRegistry meters) {
        var builder=SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"),"finguard-copilot")))
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfAttributes(16).setMaxAttributeValueLength(128)
                        .setMaxNumberOfEvents(0).setMaxNumberOfLinks(0).build());
        for(String destination:new String[]{"otlp","langfuse"}) {
            String endpoint=env.getProperty("finguard.copilot.telemetry."+destination+"-endpoint","");
            if(endpoint.isBlank()) continue;
            validateEndpoint(endpoint,destination);
            var exporter=OtlpHttpSpanExporter.builder().setEndpoint(endpoint).setTimeout(Duration.ofSeconds(2))
                    .setConnectTimeout(Duration.ofSeconds(2)).setRetryPolicy(null);
            if(destination.equals("langfuse")) {
                String publicKey=env.getProperty("COPILOT_LANGFUSE_PUBLIC_KEY","");
                String secretKey=env.getProperty("COPILOT_LANGFUSE_SECRET_KEY","");
                if(publicKey.isBlank() || secretKey.isBlank() || publicKey.contains(":") || publicKey.contains("\n") || secretKey.contains("\n"))
                    throw new IllegalArgumentException("Langfuse credentials are required and must be header-safe");
                exporter.addHeader("Authorization","Basic "+Base64.getEncoder().encodeToString((publicKey+":"+secretKey).getBytes(StandardCharsets.UTF_8)));
                exporter.addHeader("x-langfuse-ingestion-version","4");
            }
            builder.addSpanProcessor(new BoundedSpanProcessor(exporter.build(),meters,destination,1000,Duration.ofSeconds(60),Clock.systemUTC(),true));
        }
        return builder.build();
    }
    @Bean AnalysisTelemetry analysisTelemetry(SdkTracerProvider provider,MeterRegistry meters) {
        return new AnalysisTelemetry(provider.get("finguard-copilot","p4-4-v1"),meters);
    }
    public static void validateEndpoint(String endpoint,String destination) {
        try {
            URI uri=URI.create(endpoint);
            boolean local=java.util.Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost());
            if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local))
                    || !uri.getPath().equals(destination.equals("langfuse")?"/api/public/otel/v1/traces":"/v1/traces")) throw new IllegalArgumentException();
        } catch(RuntimeException ignored){throw new IllegalArgumentException("invalid telemetry endpoint configuration");}
    }
}
