package com.finguard.copilot.knowledge.embedding;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.knowledge.port.*;

/** Explicit local semantic service; artifact identity is checked on every response. No fixed-vector fallback. */
public final class LoopbackSemanticEmbeddingGateway implements EmbeddingGateway {
    private static final int BODY_LIMIT=65536;
    private final URI endpoint;
    private final String version,manifestSha;
    private final int dimensions;
    private final Duration timeout;
    private final HttpClient client;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public LoopbackSemanticEmbeddingGateway(URI endpoint,String version,String manifestSha,int dimensions,Duration timeout) {
        if(endpoint==null || !"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
            || endpoint.getPort()<1 || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
            || !Set.of("","/").contains(endpoint.getPath()) || version==null || !version.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
            || manifestSha==null || !manifestSha.matches("[a-f0-9]{64}") || dimensions!=384 || timeout==null
            || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1))>0)throw new IllegalArgumentException("Semantic embedding configuration invalid");
        this.endpoint=endpoint.resolve("/embed");this.version=version;this.manifestSha=manifestSha;this.dimensions=dimensions;this.timeout=timeout;
        client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public EmbeddingVector embed(String text,String requestedVersion){return embedQuery(text,requestedVersion);}
    @Override public EmbeddingVector embedQuery(String text,String requestedVersion){return request(text,requestedVersion,"QUERY");}
    @Override public EmbeddingVector embedDocument(String text,String requestedVersion){return request(text,requestedVersion,"DOCUMENT");}
    @Override public Map<String,Object> descriptor(){
        return Map.of("implementation","LoopbackSemanticEmbeddingGateway","artifactManifestSha256",manifestSha,
            "queryPrefix","query: ","documentPrefix","passage: ","pooling","attention-masked-mean-l2","maxTokens",512);
    }

    private EmbeddingVector request(String text,String requestedVersion,String kind) {
        if(!version.equals(requestedVersion) || text==null || text.isBlank() || text.codePointCount(0,text.length())>8192)
            throw new IllegalArgumentException("Semantic embedding input/version invalid");
        try{StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));}
        catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("Semantic embedding Unicode invalid");}
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            byte[] body=json.writeValueAsBytes(Map.of("embeddingVersion",version,"kind",kind,"text",text));
            if(body.length>BODY_LIMIT)throw new IllegalArgumentException("Semantic embedding input exceeded");
            var http=HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending=client.sendAsync(http,info->new LimitedBody());
            var response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if(response.statusCode()!=200)throw unavailable();
            JsonNode value=json.readTree(response.body());
            Set<String> fields=new HashSet<>();value.fieldNames().forEachRemaining(fields::add);
            if(!value.isObject() || !fields.equals(Set.of("embeddingVersion","dimensions","values","artifactManifestSha256"))
                || !value.path("embeddingVersion").isTextual() || !version.equals(value.path("embeddingVersion").asText())
                || !value.path("artifactManifestSha256").isTextual() || !manifestSha.equals(value.path("artifactManifestSha256").asText())
                || !value.path("dimensions").isIntegralNumber() || value.path("dimensions").asInt()!=dimensions
                || !value.path("values").isArray() || value.path("values").size()!=dimensions)throw unavailable();
            List<Double> vector=new ArrayList<>(dimensions);double squaredNorm=0;
            for(var item:value.path("values")){
                double number=item.doubleValue();
                if(!item.isNumber() || !Double.isFinite(number))throw unavailable();
                vector.add(number);squaredNorm+=number*number;
            }
            if(!Double.isFinite(squaredNorm) || Math.abs(squaredNorm-1.0)>0.001)throw unavailable();
            return new EmbeddingVector(vector);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception failed){throw unavailable();}
        finally{if(pending!=null && !pending.isDone())pending.cancel(true);}
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("Semantic embedding unavailable or response invalid");}

    /** Bound memory before accumulation and let get(timeout) cover headers plus the entire response body. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        @Override public void onNext(List<ByteBuffer> parts){
            try {
                for(var part:parts){
                    if(part.remaining()>BODY_LIMIT-bytes.size())throw unavailable();
                    byte[] chunk=new byte[part.remaining()];part.get(chunk);bytes.writeBytes(chunk);
                }
                subscription.request(1);
            }catch(RuntimeException failure){subscription.cancel();result.completeExceptionally(unavailable());}
        }
        @Override public void onError(Throwable failure){result.completeExceptionally(unavailable());}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
