package com.finguard.copilot.governance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finguard.copilot.config.*;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.knowledge.domain.*;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;
import com.finguard.copilot.model.port.ModelRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunManifestServiceTest {
    @Test void activeRetrievalConfigurationIsFrozenAsContentBoundArtifact() throws Exception {
        service("CNY","2","price-v1");
        var model=new CopilotProperties.Model("deepseek","deepseek-flash",URI.create("https://api.deepseek.com"),
            new SecretValue("test-only"),"/chat/completions",32000,2000,Duration.ofSeconds(20),"price-v1","CNY",new BigDecimal("2"),new BigDecimal("8"));
        var props=new CopilotProperties(null,new CopilotProperties.Embedding(8),new CopilotProperties.Retrieval(1),null,null,
            model,new CopilotProperties.Prompt("review-analysis-v2"),CopilotProperties.Budget.defaults());
        var descriptor=new HashMap<String,Object>(Map.of("mode","single-source-margin-v1","minimumScore",.8,"minimumMargin",.02,"candidateTopK",2,"topK",1));
        RunManifestService active;
        try {
            active=RunManifestService.class.getConstructor(VersionedArtifactStore.class,KnowledgeIndexRepository.class,CopilotProperties.class,
                NumericGuardProperties.class,ReviewedPolicyProperties.class,String.class,GovernanceProperties.class,Clock.class,CallAdmission.class,
                com.finguard.copilot.knowledge.port.EmbeddingGateway.class,Map.class)
                .newInstance(store,indexes,props,new NumericGuardProperties(List.of()),new ReviewedPolicyProperties(List.of()),"fixed-v1",
                    GovernanceProperties.defaults(),Clock.fixed(at,ZoneOffset.UTC),null,null,descriptor);
        } catch(NoSuchMethodException e) {throw new AssertionError("retrieval manifest contract missing",e);}
        descriptor.put("minimumMargin",.5);
        active.prepare(id,snapshot(),request("x"),List.of(),"idx-1");
        var manifest=ArgumentCaptor.forClass(ObjectNode.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<VersionedArtifactStore.Artifact>> artifacts=ArgumentCaptor.forClass(List.class);
        verify(store).freeze(eq(id),manifest.capture(),artifacts.capture());
        var artifact=artifacts.getValue().stream().filter(a->a.kind().equals("retrieval")).findFirst().orElseThrow();
        assertThat(artifact.content().path("minimumMargin").asDouble()).isEqualTo(.02);
        assertThat(manifest.getValue().path("retrieval").path("sha256").asText()).isEqualTo(CanonicalJson.hash(artifact.content()));
        assertThat(artifact.version()).isEqualTo("retrieval-"+CanonicalJson.hash(artifact.content()));
    }
    final UUID id=UUID.randomUUID();
    final Instant at=Instant.parse("2026-11-02T06:00:00Z");
    final VersionedArtifactStore store=mock(VersionedArtifactStore.class);
    final KnowledgeIndexRepository indexes=mock(KnowledgeIndexRepository.class);
    final KnowledgeChunk chunk=KnowledgeChunk.create("policy","v1",0,"reviewed policy","Document",Map.of());

    RunManifestService service(String currency, String input, String version) {
        when(indexes.findPublished()).thenReturn(Optional.of(new KnowledgeIndexVersion("idx-1",KnowledgeIndexStatus.PUBLISHED,at,at,null)));
        when(indexes.findChunks("idx-1")).thenReturn(List.of(chunk));
        var model=new CopilotProperties.Model("deepseek","deepseek-flash",URI.create("https://api.deepseek.com"),
                new SecretValue("never-persist-this-key"),"/chat/completions",32000,2000,Duration.ofSeconds(20),
                version,currency,new BigDecimal(input),new BigDecimal("8"));
        return configured(model,GovernanceProperties.defaults());
    }
    RunManifestService configured(CopilotProperties.Model model,GovernanceProperties governance) {
        when(indexes.findPublished()).thenReturn(Optional.of(new KnowledgeIndexVersion("idx-1",KnowledgeIndexStatus.PUBLISHED,at,at,null)));
        when(indexes.findChunks("idx-1")).thenReturn(List.of(chunk));
        var props=new CopilotProperties(null,new CopilotProperties.Embedding(8),new CopilotProperties.Retrieval(5),null,null,
                model,new CopilotProperties.Prompt("review-analysis-v2"),CopilotProperties.Budget.defaults());
        return new RunManifestService(store,indexes,props,new NumericGuardProperties(List.of()),
                new ReviewedPolicyProperties(List.of()),"fixed-v1",governance,Clock.fixed(at,ZoneOffset.UTC));
    }

    ModelRequest request(String context) {
        return new ModelRequest(id.toString(),new PromptTemplate("review-analysis-v2").render(),context,
                resourceSchema(),2000,0,"review-analysis-v2","legacy-model-label");
    }
    String resourceSchema() {
        try(var in=getClass().getResourceAsStream("/schema/analysis-output-v1.schema.json")) {
            return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        } catch(Exception e) {throw new AssertionError(e);}
    }
    FactSnapshot snapshot() {
        var facts=new ReviewTaskContext(
                new ReviewTaskContext.ReviewTask(1,0,"PENDING",null,"test",at),
                new ReviewTaskContext.Transaction(1,1,new BigDecimal("12.50"),"CNY",at,"BANK"),
                new ReviewTaskContext.Reconciliation("MATCHED","NONE","test"),List.of(),
                new ReviewTaskContext.AccountSummary("ACTIVE","masked"),at);
        return new SnapshotCodec().capture(facts);
    }
    @Test void freezesHashesWithoutPromptFactsOrCredentialsAndReturnsBoundModelVersion() {
        var result=service("CNY","2","price-v1").prepare(id,snapshot(),request("private-dynamic-facts"),List.of(),"idx-1");
        assertThat(result.modelConfigurationVersion()).startsWith("p4-model-").hasSize(73);
        var captor=ArgumentCaptor.forClass(ObjectNode.class);
        verify(store).freeze(eq(id),captor.capture(),anyList());
        var manifest=captor.getValue();
        assertThat(manifest.toString()).doesNotContain("never-persist-this-key","private-dynamic-facts","SYSTEM_POLICY");
        assertThat(manifest.path("index").path("version").asText()).isEqualTo("idx-1");
        assertThat(manifest.path("price").path("currency").asText()).isEqualTo("CNY");
        assertThat(manifest.path("inputUpperBound").asInt()).isGreaterThan(resourceSchema().length());
        assertThat(manifest.has("retrieval")).isFalse();
    }

    @Test void freezesTheBackupRouteAndPriceBeforeAcquiringTheOnlyCall() {
        service("CNY", "2", "price-v1");
        var primary = new CopilotProperties.Model("deepseek", "deepseek-flash", URI.create("https://api.deepseek.com"),
                new SecretValue("fixture-primary"), "/chat/completions", 32000, 2000, Duration.ofSeconds(20), "primary-priced");
        var backup = ReviewModelRoute.select(primary, "review-analysis-v2", true, false, new SecretValue("fixture-backup"));
        var props = new CopilotProperties(null, new CopilotProperties.Embedding(8), new CopilotProperties.Retrieval(5), null, null,
                primary, new CopilotProperties.Prompt("review-analysis-v2"), CopilotProperties.Budget.defaults());
        var governance = new GovernanceProperties(new BigDecimal("0.30"), null, null, null, null, null, false);
        var admission = mock(CallAdmission.class);
        var service = new RunManifestService(store, indexes, props, new NumericGuardProperties(List.of()),
                new ReviewedPolicyProperties(List.of()), "fixed-v1", governance, Clock.fixed(at, ZoneOffset.UTC), admission,
                null, Map.of(), backup);
        service.prepare(id, snapshot(), request("synthetic review"), List.of(), "idx-1");

        var ordered = inOrder(store, admission);
        var manifest = ArgumentCaptor.forClass(ObjectNode.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<VersionedArtifactStore.Artifact>> artifacts = ArgumentCaptor.forClass(List.class);
        ordered.verify(store).freeze(eq(id), manifest.capture(), artifacts.capture());
        ordered.verify(admission).open(id);
        ordered.verifyNoMoreInteractions();
        assertThat(manifest.getValue().path("route").path("version").asText()).isEqualTo("zhipu-review-backup-v1");
        assertThat(manifest.getValue().path("requestSha256").asText()).hasSize(64);
        var route = artifacts.getValue().stream().filter(a -> a.kind().equals("route")).findFirst().orElseThrow().content();
        assertThat(route.path("qualificationSha256").asText()).isEqualTo(ReviewModelRoute.EVALUATION_SHA256);
        assertThat(route.path("fallback").asBoolean()).isTrue();
        var price = artifacts.getValue().stream().filter(a -> a.kind().equals("price")).findFirst().orElseThrow().content();
        assertThat(price.path("provider").asText()).isEqualTo("zhipu");
        assertThat(price.path("outputPerMillion").asInt()).isEqualTo(28);
        assertThat(CanonicalJson.encode(artifacts.getValue())).doesNotContain("fixture-backup", "synthetic review");
    }
    @Test void semanticManifestIdentifiesActualModelArtifactsInsteadOfFixedVectors() {
        service("CNY","2","price-v1"); // initialize published membership
        var model=new CopilotProperties.Model("deepseek","deepseek-flash",URI.create("https://api.deepseek.com"),
            new SecretValue("test-only"),"/chat/completions",32000,2000,Duration.ofSeconds(20),"price-v1","CNY",new BigDecimal("2"),new BigDecimal("8"));
        var props=new CopilotProperties(null,new CopilotProperties.Embedding(384),new CopilotProperties.Retrieval(5),null,null,
            model,new CopilotProperties.Prompt("review-analysis-v2"),CopilotProperties.Budget.defaults());
        var embedding=new com.finguard.copilot.knowledge.embedding.LoopbackSemanticEmbeddingGateway(URI.create("http://127.0.0.1:8766"),"e5-test","a".repeat(64),384,Duration.ofSeconds(2));
        var service=new RunManifestService(store,indexes,props,new NumericGuardProperties(List.of()),new ReviewedPolicyProperties(List.of()),
            "e5-test",GovernanceProperties.defaults(),Clock.fixed(at,ZoneOffset.UTC),null,embedding);
        service.prepare(id,snapshot(),request("context"),List.of(),"idx-1");
        var artifacts=ArgumentCaptor.forClass(List.class);verify(store).freeze(eq(id),any(),artifacts.capture());
        var serialized=CanonicalJson.encode(artifacts.getValue());
        assertThat(serialized).contains("LoopbackSemanticEmbeddingGateway","artifactManifestSha256","a".repeat(64)).doesNotContain("FixedEmbeddingGateway");
    }
    @Test void rejectsUnpricedZeroOrWrongCurrencyBeforeFreezing() {
        for(var config:List.of(List.of("CNY","0","price-v1"),List.of("USD","2","price-v1"),List.of("CNY","2","e2e-unpriced-v1"))) {
            assertThatThrownBy(()->service(config.get(0),config.get(1),config.get(2)).prepare(id,snapshot(),request("x"),List.of(),"idx-1"))
                    .hasMessageContaining("price");
        }
        verifyNoInteractions(store);
    }
    @Test void rejectsUnicodePayloadBeyondBoundAndAlteredPromptOrSchema() {
        var service=service("CNY","2","price-v1");
        assertThatThrownBy(()->service.prepare(id,snapshot(),request("中😀".repeat(7000)),List.of(),"idx-1")).hasMessageContaining("input");
        var altered=new ModelRequest(id.toString(),"altered", "x",resourceSchema(),2000,0,"review-analysis-v2","x");
        assertThatThrownBy(()->service.prepare(id,snapshot(),altered,List.of(),"idx-1")).hasMessageContaining("prompt");
        verifyNoInteractions(store);
    }
    @Test void rejectsEvidenceFromDifferentIndexOrMutatedMembership() {
        var service=service("CNY","2","price-v1");
        var citation=new KnowledgeCitation("policy","v1",chunk.chunkId(),"Document",chunk.contentChecksum(),1,"old-index");
        assertThatThrownBy(()->service.prepare(id,snapshot(),request("x"),List.of(new KnowledgeEvidence(citation,chunk.content())),"idx-1"))
                .hasMessageContaining("index");
        assertThatThrownBy(()->service.prepare(id,snapshot(),request("x"),List.of(),"old-index")).hasMessageContaining("index changed");
        verifyNoInteractions(store);
    }
    @Test void canonicalHashIgnoresObjectOrderButChangesWithContent() {
        assertThat(CanonicalJson.hash(Map.of("a",1,"b",2))).isEqualTo(CanonicalJson.hash(new LinkedHashMap<>(Map.of("b",2,"a",1))));
        assertThat(CanonicalJson.hash(Map.of("a",1))).isNotEqualTo(CanonicalJson.hash(Map.of("a",2)));
    }
    @Test void rejectsMalformedUnicodeInsteadOfUnderestimatingReplacementBytes() {
        assertThatThrownBy(()->service("CNY","2","price-v1").prepare(id,snapshot(),request("\uD800".repeat(3000)),List.of(),"idx-1"))
                .hasMessageContaining("input");
        verifyNoInteractions(store);
    }
    @Test void rejectsAnUnreviewedEndpointEvenWithValidPrices() {
        var model=new CopilotProperties.Model("deepseek","deepseek-flash",URI.create("https://unreviewed.invalid"),
                new SecretValue("test-only"),32000,2000,Duration.ofSeconds(20),"price-v1");
        assertThatThrownBy(()->new DeepseekTextTokenBound().upperBound(model,request("x"))).hasMessageContaining("protocol");
    }
    @Test void zeroPriceIsLimitedToExplicitLoopbackFixtureMode() {
        var defaults=GovernanceProperties.defaults();
        var fixture=new GovernanceProperties(defaults.maxCallCny(),defaults.priceValidFrom(),defaults.priceValidUntil(),
                defaults.maxReviewerDailyCny(),defaults.maxProviderDailyCny(),defaults.maxGlobalDailyCny(),true);
        var model=new CopilotProperties.Model("deepseek","local-test-stub",URI.create("http://127.0.0.1:18765"),
                new SecretValue("test-only"),"/chat/completions",32000,2000,Duration.ofSeconds(20),"fixture-zero-v1","CNY",BigDecimal.ZERO,BigDecimal.ZERO);
        assertThatThrownBy(()->configured(model,defaults).prepare(id,snapshot(),request("x"),List.of(),"idx-1"));
        assertThat(configured(model,fixture).prepare(id,snapshot(),request("x"),List.of(),"idx-1")).isNotNull();
        var remote=new CopilotProperties.Model("deepseek","deepseek-flash",URI.create("https://api.deepseek.com"),
                new SecretValue("test-only"),"/chat/completions",32000,2000,Duration.ofSeconds(20),"fixture-zero-v1","CNY",BigDecimal.ZERO,BigDecimal.ZERO);
        assertThatThrownBy(()->configured(remote,fixture).prepare(id,snapshot(),request("x"),List.of(),"idx-1")).hasMessageContaining("fixture");
    }
}
