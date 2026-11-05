package com.finguard.copilot.governance;

import java.io.InputStream;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.config.*;
import com.finguard.copilot.context.*;
import com.finguard.copilot.guardrail.CoreLargeAmountPolicyGuard;
import com.finguard.copilot.knowledge.domain.*;
import com.finguard.copilot.knowledge.persistence.KnowledgeIndexRepository;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;
import com.finguard.copilot.model.port.ModelRequest;
import com.finguard.copilot.governance.VersionedArtifactStore.Artifact;

public final class RunManifestService implements ModelCallPreflight {
    private final VersionedArtifactStore store;
    private final KnowledgeIndexRepository indexes;
    private final CopilotProperties properties;
    private final JsonNode policyConfiguration;
    private final String embeddingVersion;
    private final GovernanceProperties governance;
    private final Clock clock;
    private final CallAdmission admission;
    private final com.finguard.copilot.knowledge.port.EmbeddingGateway embedding;
    private final Map<String,Object> retrievalDescriptor;
    public RunManifestService(VersionedArtifactStore store,KnowledgeIndexRepository indexes,CopilotProperties properties,
            NumericGuardProperties numeric,ReviewedPolicyProperties reviewed,String embeddingVersion,GovernanceProperties governance,Clock clock) {
        this(store,indexes,properties,numeric,reviewed,embeddingVersion,governance,clock,null);
    }
    public RunManifestService(VersionedArtifactStore store,KnowledgeIndexRepository indexes,CopilotProperties properties,
            NumericGuardProperties numeric,ReviewedPolicyProperties reviewed,String embeddingVersion,GovernanceProperties governance,Clock clock,CallAdmission admission) {
        this(store,indexes,properties,numeric,reviewed,embeddingVersion,governance,clock,admission,null);
    }
    public RunManifestService(VersionedArtifactStore store,KnowledgeIndexRepository indexes,CopilotProperties properties,
            NumericGuardProperties numeric,ReviewedPolicyProperties reviewed,String embeddingVersion,GovernanceProperties governance,Clock clock,CallAdmission admission,
            com.finguard.copilot.knowledge.port.EmbeddingGateway embedding) {
        this(store,indexes,properties,numeric,reviewed,embeddingVersion,governance,clock,admission,embedding,Map.of());
    }
    public RunManifestService(VersionedArtifactStore store,KnowledgeIndexRepository indexes,CopilotProperties properties,
            NumericGuardProperties numeric,ReviewedPolicyProperties reviewed,String embeddingVersion,GovernanceProperties governance,Clock clock,CallAdmission admission,
            com.finguard.copilot.knowledge.port.EmbeddingGateway embedding,Map<String,Object> retrievalDescriptor) {
        this.store=Objects.requireNonNull(store);this.indexes=Objects.requireNonNull(indexes);this.properties=properties;
        this.policyConfiguration=CanonicalJson.tree(Map.of("numeric",numeric,"reviewed",reviewed,
                "corePolicySha256",CoreLargeAmountPolicyGuard.CONTENT_SHA256));
        this.embeddingVersion=Objects.requireNonNull(embeddingVersion);this.governance=governance;this.clock=clock;
        this.admission=admission;
        this.embedding=embedding;
        this.retrievalDescriptor=Map.copyOf(retrievalDescriptor);
    }

    @Override public ModelRequest prepare(UUID analysisId,FactSnapshot snapshot,ModelRequest request,List<KnowledgeEvidence> evidence,String retrievedIndexVersion) {
        var model=properties.model();
        boolean fixture=governance.fixtureMode();
        if(fixture && !DeepseekTextTokenBound.fixtureEndpoint(model)) throw rejected("fixture mode requires the loopback test model");
        if(!analysisId.toString().equals(request.requestId())) throw rejected("request identity mismatch");
        if(!properties.prompt().version().equals(request.promptVersion()) ||
                !new PromptTemplate(request.promptVersion()).render().equals(request.systemInstruction())) throw rejected("prompt content mismatch");
        if(!resource("/schema/analysis-output-v1.schema.json").equals(request.outputSchemaJson())) throw rejected("schema content mismatch");
        String version=model.priceTableVersion();
        if(!"CNY".equals(model.currency()) || (fixture ? model.inputPricePerMillion().signum()<0 : model.inputPricePerMillion().signum()<=0) ||
                (fixture ? model.outputPricePerMillion().signum()<0 : model.outputPricePerMillion().signum()<=0) ||
                version==null || version.contains("unpriced") || version.equals("unset") ||
                clock.instant().isBefore(governance.priceValidFrom()) || !clock.instant().isBefore(governance.priceValidUntil()))
            throw rejected("price must be positive, effective and CNY priced");
        if(request.maxOutputTokens()>Math.min(model.outputTokenLimit(),properties.budget().maxOutputTokens())) throw rejected("output cap mismatch");
        int upper=new DeepseekTextTokenBound().upperBound(model,request,fixture);
        if(upper>Math.min(model.inputTokenLimit(),properties.budget().maxInputTokens())) throw rejected("input upper bound exceeds limit");
        BigDecimal reserve=model.inputPricePerMillion().multiply(BigDecimal.valueOf(upper))
                .add(model.outputPricePerMillion().multiply(BigDecimal.valueOf(request.maxOutputTokens())))
                .divide(new BigDecimal("1000000"),8,RoundingMode.CEILING);
        if(reserve.compareTo(governance.maxCallCny())>0) throw rejected("price reservation exceeds call budget");

        var artifacts=new ArrayList<Artifact>();
        ObjectNode manifest=JsonNodeFactory.instance.objectNode();
        manifest.put("schemaVersion","model-run-manifest-v1");manifest.put("analysisId",analysisId.toString());
        manifest.put("reviewTaskId",snapshot.facts().reviewTask().id());manifest.put("reviewTaskVersion",snapshot.facts().reviewTask().version());
        manifest.put("snapshotSha256",HexFormat.of().formatHex(snapshot.sha256()));
        var modelContent=Map.of("provider",model.provider(),"modelId",model.modelId(),"endpoint",model.baseUrl().toString(),
                "path",model.chatCompletionsPath(),"inputLimit",model.inputTokenLimit(),"outputLimit",model.outputTokenLimit(),
                "timeout",model.timeout().toString(),"mode",fixture?"loopback-fixture":"text-json-non-thinking","temperature",request.temperature(),"dataClass","sanitized-review");
        String modelVersion="p4-model-"+CanonicalJson.hash(modelContent);
        bind(manifest,artifacts,"model",modelVersion,modelContent);
        bind(manifest,artifacts,"prompt",request.promptVersion(),Map.of("sha256",CanonicalJson.sha256(request.systemInstruction())));
        bind(manifest,artifacts,"schema","analysis-output-v1",Map.of("sha256",CanonicalJson.sha256(request.outputSchemaJson())));
        bind(manifest,artifacts,"policy","policy-"+CanonicalJson.hash(policyConfiguration),policyConfiguration);
        var embeddingIdentity=new TreeMap<String,Object>(Map.of("version",embeddingVersion,"dimensions",properties.embedding().dimensions(),
                "implementation","FixedEmbeddingGateway"));
        if(embedding!=null)embeddingIdentity.putAll(embedding.descriptor());
        bind(manifest,artifacts,"embedding",embeddingVersion,embeddingIdentity);
        if(!retrievalDescriptor.isEmpty())
            bind(manifest,artifacts,"retrieval","retrieval-"+CanonicalJson.hash(retrievalDescriptor),retrievalDescriptor);
        bind(manifest,artifacts,"price",version,Map.of("provider",model.provider(),"modelId",model.modelId(),"currency",model.currency(),
                "inputPerMillion",model.inputPricePerMillion(),"outputPerMillion",model.outputPricePerMillion(),
                "validFrom",governance.priceValidFrom().toString(),"validUntil",governance.priceValidUntil().toString(),"basis","peak-cache-miss"));
        ((ObjectNode)manifest.get("price")).put("currency",model.currency());
        bind(manifest,artifacts,"tokenBound",fixture?"fixture-byte-bound-v1":DeepseekTextTokenBound.VERSION,Map.of("recipeRevision",fixture?"loopback-fixture-v1":DeepseekTextTokenBound.RECIPE_REVISION,
                "tokenizerSha256",fixture?"not-applicable":DeepseekTextTokenBound.TOKENIZER_SHA256,"method","wire-utf8-bytes-plus-1024","protocol","two-text-messages-json-object-thinking-disabled"));
        bind(manifest,artifacts,"route",fixture?"fixture-primary-v1":"single-primary-v1",Map.of("provider","deepseek","modelId",model.modelId(),"reason",fixture?"explicit-test-fixture":"only-qualified-primary","fallback",false));
        var limits=Map.of("maxCallCny",governance.maxCallCny(),"maxCalls",1,"maxInputTokens",properties.budget().maxInputTokens(),
                "maxOutputTokens",properties.budget().maxOutputTokens(),"maxReviewerDailyCny",governance.maxReviewerDailyCny(),
                "maxProviderDailyCny",governance.maxProviderDailyCny(),"maxGlobalDailyCny",governance.maxGlobalDailyCny());
        bind(manifest,artifacts,"budget","budget-"+CanonicalJson.hash(limits),limits);
        bindIndex(manifest,artifacts,evidence,retrievedIndexVersion);
        manifest.put("inputUpperBound",upper);manifest.put("outputCap",request.maxOutputTokens());manifest.put("reservedEstimateCny",reserve.toPlainString());
        manifest.put("fixture",fixture);
        try {
            manifest.put("requestSha256",CanonicalJson.hash(com.finguard.copilot.model.adapter.openai.CompatibleChatRequestPayload.create(model,request)));
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw rejected("request serialization failed");}
        store.freeze(analysisId,manifest,artifacts);
        if(admission!=null) admission.open(analysisId);
        return new ModelRequest(request.requestId(),request.systemInstruction(),request.context(),request.outputSchemaJson(),
                request.maxOutputTokens(),request.temperature(),request.promptVersion(),modelVersion);
    }

    private void bindIndex(ObjectNode manifest,List<Artifact> artifacts,List<KnowledgeEvidence> evidence,String retrievedIndexVersion) {
        String index=indexes.findPublished().map(KnowledgeIndexVersion::indexVersion).orElseThrow(()->rejected("no published index"));
        if(!index.equals(retrievedIndexVersion)) throw rejected("published index changed after retrieval");
        var chunks=indexes.findChunks(index).stream().sorted(Comparator.comparing(KnowledgeChunk::chunkId)).toList();
        if(chunks.isEmpty() || chunks.stream().map(KnowledgeChunk::chunkId).distinct().count()!=chunks.size()) throw rejected("invalid index membership");
        for(var chunk:chunks) if(!CanonicalJson.sha256(chunk.content()).equals(chunk.contentChecksum())) throw rejected("index member content mismatch");
        for(var item:evidence) {
            var c=item.citation();
            if(!index.equals(c.indexVersion()) || chunks.stream().noneMatch(chunk->chunk.chunkId().equals(c.chunkId()) &&
                    chunk.documentId().equals(c.documentId()) && chunk.documentVersion().equals(c.documentVersion()) &&
                    chunk.sectionPath().equals(c.sectionPath()) && chunk.contentChecksum().equals(c.contentChecksum()) && chunk.content().equals(item.excerpt())))
                throw rejected("retrieved evidence does not match published index");
        }
        var members=chunks.stream().map(chunk->Map.of("chunkId",chunk.chunkId(),"documentId",chunk.documentId(),
                "version",chunk.documentVersion(),"section",chunk.sectionPath(),"sha256",chunk.contentChecksum())).toList();
        bind(manifest,artifacts,"index",index,members);
        manifest.set("retainedEvidence",CanonicalJson.tree(evidence.stream().map(item->Map.of("chunkId",item.citation().chunkId(),
                "sha256",item.citation().contentChecksum())).toList()));
    }
    private void bind(ObjectNode manifest,List<Artifact> artifacts,String kind,String version,Object content) {
        var tree=CanonicalJson.tree(content);artifacts.add(new Artifact(kind,version,tree));
        manifest.set(kind,CanonicalJson.tree(Map.of("version",version,"sha256",CanonicalJson.hash(tree))));
    }
    private String resource(String path) {
        try(InputStream input=getClass().getResourceAsStream(path)) { return new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8); }
        catch(Exception e) {throw rejected("required schema unavailable");}
    }
    public static CopilotException rejected(String reason) { return new CopilotException(ErrorCode.MODEL_CONFIGURATION_INVALID,reason); }
}
