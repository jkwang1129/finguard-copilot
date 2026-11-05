package com.finguard.copilot.knowledge.embedding;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

class LoopbackSemanticEmbeddingGatewayTest {
    final ObjectMapper json=new ObjectMapper();
    final String version="e5-small-int8-test",sha="a".repeat(64);
    final List<JsonNode> requests=new ArrayList<>();
    final AtomicInteger status=new AtomicInteger(200);
    HttpServer server;String response;long delay;
    @BeforeEach void start() throws Exception {
        response=valid().toString();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/embed",exchange->{
            try(exchange){requests.add(json.readTree(exchange.getRequestBody()));
                if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);
            }catch(java.io.IOException ignored){}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    URI endpoint(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
    LoopbackSemanticEmbeddingGateway gateway(){return new LoopbackSemanticEmbeddingGateway(endpoint(),version,sha,384,Duration.ofSeconds(2));}
    ObjectNode valid(){
        var root=json.createObjectNode().put("embeddingVersion",version).put("artifactManifestSha256",sha).put("dimensions",384);
        var values=root.putArray("values");values.add(1.0);for(int n=1;n<384;n++)values.add(0.0);return root;
    }
    @Test void queryAndDocumentCarryDistinctRolesAndPinnedIdentity() {
        var gateway=gateway();
        assertThat(gateway.embedQuery("金额阈值的审核依据",version).dimension()).isEqualTo(384);
        assertThat(gateway.embedDocument("项目审核政策。",version).values().get(0)).isEqualTo(1.0);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path("kind").asText()).isEqualTo("QUERY");
        assertThat(requests.get(1).path("kind").asText()).isEqualTo("DOCUMENT");
        assertThat(requests.get(0).path("text").asText()).isEqualTo("金额阈值的审核依据");
        assertThat(requests.get(0).path("embeddingVersion").asText()).isEqualTo(version);
        assertThat(gateway.descriptor()).containsEntry("artifactManifestSha256",sha).containsEntry("implementation","LoopbackSemanticEmbeddingGateway");
    }
    @Test void versionAndInputRejectionsOccurBeforeHttp() {
        var gateway=gateway();
        assertThatThrownBy(()->gateway.embedQuery("query","wrong-version")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->gateway.embedQuery("x".repeat(8193),version)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->gateway.embedQuery(Character.toString((char)0xd800),version)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }
    @Test void remoteCredentialAndWrongDimensionConfigurationCannotBeUsed() {
        for(String url:List.of("https://127.0.0.1:9000","http://localhost:9000","http://127.0.0.1:9000/path","http://user:password@127.0.0.1:9000","http://example.com:9000"))
            assertThatThrownBy(()->new LoopbackSemanticEmbeddingGateway(URI.create(url),version,sha,384,Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new LoopbackSemanticEmbeddingGateway(endpoint(),version,sha,8,Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void wrongIdentityDimensionAndNonNormalizedVectorsFailClosed() {
        for(String field:List.of("embeddingVersion","artifactManifestSha256","dimensions","values")){
            var body=valid();
            if(field.equals("dimensions"))body.put(field,8);
            else if(field.equals("values"))((ArrayNode)body.get(field)).set(0,DoubleNode.valueOf(2.0));
            else body.put(field,"wrong");
            response=body.toString();assertThatThrownBy(()->gateway().embedQuery("query",version)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void malformedExtraOversizedAndNonNumericResponsesAreRejected() {
        var bad=valid();((ArrayNode)bad.get("values")).set(1,TextNode.valueOf("NaN"));
        for(String body:List.of("{}",valid().put("unexpected",true).toString(),bad.toString()," ".repeat(65537),valid()+"{}")){
            response=body;assertThatThrownBy(()->gateway().embedQuery("query",version)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void serviceErrorsDoNotFallBackToFixedEmbeddings() {
        status.set(503);assertThatThrownBy(()->gateway().embedDocument("document",version)).isInstanceOf(IllegalStateException.class);
        assertThat(requests).hasSize(1);
    }
    @Test void responseTimeoutIsBounded() {
        delay=250;
        var gateway=new LoopbackSemanticEmbeddingGateway(endpoint(),version,sha,384,Duration.ofMillis(50));
        assertThatThrownBy(()->gateway.embedQuery("query",version)).isInstanceOf(IllegalStateException.class);
    }
}
