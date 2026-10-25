package com.finguard.copilot.coreclient.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CoreClientException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpCoreClientTimeoutTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsReadTimeoutToUnavailableWithoutExposingBearerToken() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/auth/me", exchange -> {
            try {
                TimeUnit.MILLISECONDS.sleep(300);
                exchange.sendResponseHeaders(200, 0);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        String bearerToken = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.timeout-signature";
        CoreClient client = new CoreHttpConfiguration().coreClient(
                RestClient.builder(),
                new CopilotProperties(
                        new CopilotProperties.Core(
                                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                Duration.ofSeconds(1),
                                Duration.ofMillis(50)
                        ),
                        new CopilotProperties.Embedding(8),
                        new CopilotProperties.Retrieval(5)
                ),
                new ObjectMapper().findAndRegisterModules()
        );

        Logger logger = (Logger) LoggerFactory.getLogger(HttpCoreClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> client.currentUser(bearerToken))
                    .isInstanceOf(CoreClientException.class)
                    .satisfies(exception -> {
                        CoreClientException coreException = (CoreClientException) exception;
                        assertThat(coreException.kind()).isEqualTo(CoreClientException.Kind.UNAVAILABLE);
                        assertThat(coreException).hasNoCause();
                        assertThat(coreException.getMessage()).doesNotContain(bearerToken);
                    });
            assertThat(appender.list)
                    .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(bearerToken));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
