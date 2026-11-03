package com.finguard.copilot.observability;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OtlpExportTest {
    @Test void langfuseReceivesAuthenticatedProtobufContainingOnlySanitizedData() throws Exception {
        var received=new CountDownLatch(1);var body=new AtomicReference<byte[]>();var auth=new AtomicReference<String>();
        var type=new AtomicReference<String>();var ingestion=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/public/otel/v1/traces",exchange->{
            body.set(exchange.getRequestBody().readAllBytes());auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            ingestion.set(exchange.getRequestHeaders().getFirst("x-langfuse-ingestion-version"));
            type.set(exchange.getRequestHeaders().getFirst("Content-Type"));exchange.sendResponseHeaders(200,0);exchange.close();received.countDown();
        });server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(TelemetryConfiguration.class)
                    .withBean(MeterRegistry.class,SimpleMeterRegistry::new)
                    .withPropertyValues("finguard.copilot.telemetry.langfuse-endpoint=http://127.0.0.1:"+server.getAddress().getPort()+"/api/public/otel/v1/traces",
                            "COPILOT_LANGFUSE_PUBLIC_KEY=pk-test","COPILOT_LANGFUSE_SECRET_KEY=SECRET_CREDENTIAL_CANARY")
                    .run(context->{
                        assertThat(context).hasNotFailed();
                        var span=context.getBean(SdkTracerProvider.class).get("SECRET_SCOPE_CANARY").spanBuilder("SECRET_NAME_CANARY").startSpan();
                        span.setAttribute("prompt","SECRET_PROMPT_CANARY");span.recordException(new RuntimeException("SECRET_EXCEPTION_CANARY"));span.end();
                        assertThat(received.await(4,TimeUnit.SECONDS)).isTrue();
                        String bytes=new String(body.get(),java.nio.charset.StandardCharsets.ISO_8859_1);
                        assertThat(bytes).contains("finguard-copilot","copilot.unknown").doesNotContain("SECRET_");
                        assertThat(type.get()).isEqualTo("application/x-protobuf");
                        assertThat(ingestion.get()).isEqualTo("4");
                        assertThat(auth.get()).isEqualTo("Basic "+java.util.Base64.getEncoder().encodeToString("pk-test:SECRET_CREDENTIAL_CANARY".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    });
        } finally {server.stop(0);}
    }
    @Test void unreachableExporterCannotBlockBusinessOrTriggerBusinessRetry() {
        new ApplicationContextRunner().withUserConfiguration(TelemetryConfiguration.class).withBean(MeterRegistry.class,SimpleMeterRegistry::new)
                .withPropertyValues("finguard.copilot.telemetry.otlp-endpoint=http://127.0.0.1:1/v1/traces")
                .run(context->{
                    var t=context.getBean(AnalysisTelemetry.class);var calls=new java.util.concurrent.atomic.AtomicInteger();
                    long begin=System.nanoTime();
                    for(int i=0;i<100;i++) assertThat(t.call(AnalysisTelemetry.Stage.MODEL,calls::incrementAndGet)).isEqualTo(i+1);
                    assertThat(calls.get()).isEqualTo(100);
                    assertThat(Duration.ofNanos(System.nanoTime()-begin)).isLessThan(Duration.ofSeconds(1));
                });
    }
    @Test void unsafeEndpointErrorsDoNotEchoTheEndpointOrCredentials() {
        for(String url:new String[]{"https://user:SECRET@example.com/v1/traces","http://remote.example/v1/traces","https://example.com/v1/traces?SECRET","https://example.com/wrong","SECRET"})
            assertThatThrownBy(()->TelemetryConfiguration.validateEndpoint(url,"otlp")).isInstanceOf(IllegalArgumentException.class).hasMessage("invalid telemetry endpoint configuration");
    }
}
