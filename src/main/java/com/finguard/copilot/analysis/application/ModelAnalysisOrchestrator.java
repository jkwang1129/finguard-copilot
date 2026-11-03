package com.finguard.copilot.analysis.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.analysis.domain.AnalysisRecommendation;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.context.ContextAssembler;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.guardrail.NumericThresholdGuard;
import com.finguard.copilot.guardrail.ReviewedPolicyGuard;
import com.finguard.copilot.guardrail.CoreLargeAmountFactGuard;
import com.finguard.copilot.guardrail.CoreLargeAmountPolicyGuard;
import com.finguard.copilot.guardrail.UntrustedInstructionGuard;
import com.finguard.copilot.model.port.ModelGateway;
import com.finguard.copilot.model.port.ModelRequest;
import com.finguard.copilot.model.port.ModelResponse;
import com.finguard.copilot.model.port.ModelUsage;
import com.finguard.copilot.model.validation.StructuredAnalysisParser;
import com.finguard.copilot.knowledge.retrieval.KnowledgeEvidence;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalException;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;

public final class ModelAnalysisOrchestrator implements AnalysisOrchestrator {

    @Override
    public boolean requiresModelCall() {
        return true;
    }

    @Override public boolean managesModelReservation() {return preflight!=null && preflight.managesReservation();}
    @Override public void modelCallOutcomeUnknown(UUID id) {if(preflight!=null) preflight.outcomeUnknown(id);}
    @Override public void modelCallOutcomeUnknown(UUID id,ModelUsage usage) {if(preflight!=null) preflight.outcomeUnknown(id,usage);}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ModelAnalysisOrchestrator.class);

    private static final String OUTPUT_SCHEMA_RESOURCE = "/schema/analysis-output-v1.schema.json";
    private static final String MODEL_CONFIGURATION_VERSION_PREFIX = "phase3-model-v1";

    private final ModelGateway modelGateway;
    private final ContextAssembler contextAssembler;
    private final StructuredAnalysisParser parser;
    private final CopilotProperties.Model model;
    private final CopilotProperties.Budget budget;
    private final KnowledgeRetrievalService retrievalService;
    private final int retrievalTopK;
    private final NumericThresholdGuard numericThresholdGuard;
    private final ReviewedPolicyGuard reviewedPolicyGuard;
    private final String outputSchemaJson;
    private final com.finguard.copilot.governance.ModelCallPreflight preflight;
    private com.finguard.copilot.observability.AnalysisTelemetry telemetry=com.finguard.copilot.observability.AnalysisTelemetry.noop();
    public ModelAnalysisOrchestrator withTelemetry(com.finguard.copilot.observability.AnalysisTelemetry value) {telemetry=Objects.requireNonNull(value);return this;}

    public ModelAnalysisOrchestrator(
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            StructuredAnalysisParser parser,
            CopilotProperties.Model model,
            CopilotProperties.Budget budget
    ) {
        this(modelGateway, contextAssembler, parser, model, budget, null, 1);
    }

    public ModelAnalysisOrchestrator(
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            StructuredAnalysisParser parser,
            CopilotProperties.Model model,
            CopilotProperties.Budget budget,
            KnowledgeRetrievalService retrievalService,
            int retrievalTopK
    ) {
        this(modelGateway, contextAssembler, parser, model, budget, retrievalService, retrievalTopK,
                new NumericThresholdGuard(List.of()));
    }

    public ModelAnalysisOrchestrator(
            ModelGateway modelGateway,
            ContextAssembler contextAssembler,
            StructuredAnalysisParser parser,
            CopilotProperties.Model model,
            CopilotProperties.Budget budget,
            KnowledgeRetrievalService retrievalService,
            int retrievalTopK,
            NumericThresholdGuard numericThresholdGuard
    ) {
        this(modelGateway,contextAssembler,parser,model,budget,retrievalService,retrievalTopK,numericThresholdGuard,
                new ReviewedPolicyGuard(List.of()));
    }

    public ModelAnalysisOrchestrator(ModelGateway modelGateway,ContextAssembler contextAssembler,
            StructuredAnalysisParser parser,CopilotProperties.Model model,CopilotProperties.Budget budget,
            KnowledgeRetrievalService retrievalService,int retrievalTopK,NumericThresholdGuard numericThresholdGuard,
            ReviewedPolicyGuard reviewedPolicyGuard) {
        this(modelGateway,contextAssembler,parser,model,budget,retrievalService,retrievalTopK,numericThresholdGuard,reviewedPolicyGuard,null);
    }

    public ModelAnalysisOrchestrator(ModelGateway modelGateway,ContextAssembler contextAssembler,
            StructuredAnalysisParser parser,CopilotProperties.Model model,CopilotProperties.Budget budget,
            KnowledgeRetrievalService retrievalService,int retrievalTopK,NumericThresholdGuard numericThresholdGuard,
            ReviewedPolicyGuard reviewedPolicyGuard,com.finguard.copilot.governance.ModelCallPreflight preflight) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler must not be null");
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.retrievalService = retrievalService;
        if (retrievalTopK < 1) {
            throw new IllegalArgumentException("retrievalTopK must be positive");
        }
        this.retrievalTopK = retrievalTopK;
        this.numericThresholdGuard = Objects.requireNonNull(numericThresholdGuard, "numericThresholdGuard must not be null");
        this.reviewedPolicyGuard = Objects.requireNonNull(reviewedPolicyGuard);
        if(reviewedPolicyGuard.ruleCodes().stream().anyMatch(numericThresholdGuard.ruleCodes()::contains))
            throw new IllegalArgumentException("one rule cannot have multiple directional authority types");
        this.outputSchemaJson = loadOutputSchema();
        this.preflight=preflight;
    }

    @Override
    public AnalysisResult analyze(UUID analysisId, FactSnapshot snapshot) {
        return execute(analysisId, snapshot).result();
    }

    @Override
    public AnalysisExecution execute(UUID analysisId, FactSnapshot snapshot) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        KnowledgeRetrievalService.RetrievalBatch batch=null;
        if(preflight!=null) {
            if(retrievalService==null) throw com.finguard.copilot.governance.RunManifestService.rejected("published index retrieval unavailable");
            try {
                batch=telemetry.call(com.finguard.copilot.observability.AnalysisTelemetry.Stage.RETRIEVAL,()->retrievalService.retrieveBatch(snapshot.facts().reviewTask().ruleCode()+" "+snapshot.facts().reviewTask().reasonCode(),retrievalTopK));
            } catch(KnowledgeRetrievalException e) {throw com.finguard.copilot.governance.RunManifestService.rejected("published index retrieval failed");}
        }
        List<KnowledgeEvidence> evidence = batch==null?telemetry.call(com.finguard.copilot.observability.AnalysisTelemetry.Stage.RETRIEVAL,()->retrievePolicyEvidence(snapshot)):batch.evidence();
        ContextAssembler.AssembledContext assembled = telemetry.call(com.finguard.copilot.observability.AnalysisTelemetry.Stage.CONTEXT,()->retrievalService == null
                ? contextAssembler.assemble(snapshot.facts(), List.of(), defaultReviewerInput(snapshot))
                : contextAssembler.assembleRetrieved(snapshot.facts(), evidence, defaultReviewerInput(snapshot)));
        String modelConfigurationVersion = modelConfigurationVersion();
        String promptVersion = assembled.promptVersion();
        ModelRequest request = new ModelRequest(
                analysisId.toString(),
                assembled.sections().get("system"),
                renderContext(analysisId, assembled),
                outputSchemaJson,
                Math.min(budget.maxOutputTokens(), model.outputTokenLimit()),
                0d,
                promptVersion,
                modelConfigurationVersion);
        if(preflight!=null) {
            ModelRequest unprepared=request;String indexVersion=batch.indexVersion();
            request=telemetry.call(com.finguard.copilot.observability.AnalysisTelemetry.Stage.PREFLIGHT,()->preflight.prepare(analysisId,snapshot,unprepared,assembled.retainedEvidence(),indexVersion));
            modelConfigurationVersion=request.modelConfigurationVersion();
        }
        ModelResponse response;
        Instant started = Instant.now();
        try {
            ModelRequest prepared=request;
            response = telemetry.call(com.finguard.copilot.observability.AnalysisTelemetry.Stage.MODEL,()->{
                telemetry.modelMetadata(model.provider(),model.modelId(),prepared.promptVersion(),prepared.modelConfigurationVersion());
                return modelGateway.complete(prepared);
            });
        } catch (CopilotException exception) {
            throw failure(exception.code(), modelConfigurationVersion, promptVersion,
                    new ModelUsage(0, 0, elapsedSince(started)), outcome(exception.code()));
        } catch (RuntimeException exception) {
            throw failure(ErrorCode.MODEL_UNAVAILABLE, modelConfigurationVersion, promptVersion,
                    new ModelUsage(0, 0, elapsedSince(started)), "FAILURE");
        } finally {
            if(preflight!=null) preflight.afterCall(analysisId);
        }
        if (response == null) {
            throw failure(ErrorCode.MODEL_OUTPUT_INVALID, modelConfigurationVersion, promptVersion,
                    new ModelUsage(0, 0, elapsedSince(started)), "FAILURE");
        }

        try {
            AnalysisResult result = telemetry.call(com.finguard.copilot.observability.AnalysisTelemetry.Stage.VALIDATION,()->{
                AnalysisResult parsed=parser.parse(response.structuredContent());
                if(!analysisId.equals(parsed.analysisId())) throw new CopilotException(ErrorCode.MODEL_OUTPUT_INVALID,"model output analysis id does not match request");
                validatePolicy(snapshot,parsed,assembled);return parsed;
            });
            if (!analysisId.equals(result.analysisId())) {
                throw new CopilotException(ErrorCode.MODEL_OUTPUT_INVALID,
                        "model output analysis id does not match request");
            }
            return new AnalysisExecution(result, response.provider(), modelConfigurationVersion,
                    assembled.promptVersion(), response.usage());
        } catch (CopilotException exception) {
            LOG.warn("Model result rejected: code={}", exception.code());
            throw failure(exception.code(), modelConfigurationVersion, promptVersion,
                    response.usage(), "FAILURE");
        }
    }

    @Override
    public void validateStoredResult(FactSnapshot snapshot,AnalysisResult result) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(result);
        // Worker has already checked schema and immutable facts. A citation-free refusal
        // makes no directional or policy claim and requires no live knowledge service.
        if(result.recommendation()==AnalysisRecommendation.INSUFFICIENT_EVIDENCE && result.policyCitations().isEmpty()) return;
        var evidence=retrievePolicyEvidence(snapshot);
        var assembled=contextAssembler.assembleRetrieved(snapshot.facts(),evidence,defaultReviewerInput(snapshot));
        validatePolicy(snapshot,result,assembled);
    }

    private void validatePolicy(FactSnapshot snapshot,AnalysisResult result,ContextAssembler.AssembledContext assembled) {
        validateKnowledgeCitations(result,assembled);
        new UntrustedInstructionGuard().validate(assembled.sections().get("reviewerInput"),assembled.retainedEvidence(),result.recommendation());
        new CoreLargeAmountFactGuard().validate(snapshot.facts(),result.recommendation());
        new CoreLargeAmountPolicyGuard().validate(snapshot.facts(),result,assembled.retainedEvidence());
        if(result.recommendation()==AnalysisRecommendation.INSUFFICIENT_EVIDENCE) return;
        String rule=snapshot.facts().reviewTask().ruleCode();
        if(rule==null) throw new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,"directional output requires a reviewed rule");
        if("LARGE_AMOUNT".equals(rule)) {
            if(assembled.retainedEvidence().size()!=1) throw new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,"unreviewed additional Core policy evidence");
        } else if(numericThresholdGuard.ruleCodes().contains(rule)) {
            numericThresholdGuard.validate(snapshot.facts(),result,assembled.retainedEvidence());
        } else {
            reviewedPolicyGuard.validate(snapshot.facts(),result,assembled.retainedEvidence());
        }
    }

    private ModelInvocationException failure(
            ErrorCode code,
            String modelConfigurationVersion,
            String promptVersion,
            ModelUsage usage,
            String outcome
    ) {
        return new ModelInvocationException(code, model.provider(), modelConfigurationVersion,
                promptVersion, usage, outcome);
    }

    private String renderContext(UUID analysisId, ContextAssembler.AssembledContext context) {
        return "ANALYSIS_REQUEST\nanalysisId=" + analysisId
                + "\nReturn this exact analysisId in the output JSON. "
                + "For policyCitations copy the supplied citation objects exactly, including the complete excerpt. "
                + "Do not abbreviate, reword or remove headings from excerpts. "
                + "businessEvidence.sourcePaths may only contain "
                + com.finguard.copilot.guardrail.FactConsistencyValidator.ALLOWED_SOURCE_PATHS.stream().sorted().toList()
                + ". Copy transactionId from transaction.id and all business evidence values exactly from Core facts.\nCORE_FACTS\n"
                + context.sections().get("facts")
                + "\nKNOWLEDGE_EVIDENCE\n" + context.sections().get("knowledge")
                + "\nREVIEWER_INPUT\n" + context.sections().get("reviewerInput");
    }

    private String defaultReviewerInput(FactSnapshot snapshot) {
        return "Review the captured Core facts for review task "
                + snapshot.facts().reviewTask().id()
                + " using only retrieved policy evidence.";
    }

    private String modelConfigurationVersion() {
        return MODEL_CONFIGURATION_VERSION_PREFIX + ":" + model.provider() + ":" + model.modelId();
    }

    private List<KnowledgeEvidence> retrievePolicyEvidence(FactSnapshot snapshot) {
        if (retrievalService == null) {
            return List.of();
        }
        String query = snapshot.facts().reviewTask().ruleCode()
                + " " + snapshot.facts().reviewTask().reasonCode();
        try {
            return retrievalService.retrieve(query, retrievalTopK);
        } catch (KnowledgeRetrievalException exception) {
            throw new CopilotException(ErrorCode.KNOWLEDGE_UNAVAILABLE,
                    "published knowledge retrieval is unavailable");
        }
    }

    private void validateKnowledgeCitations(
            AnalysisResult result,
            ContextAssembler.AssembledContext context
    ) {
        Set<String> retained = Set.copyOf(context.retainedDocumentIds());
        if ((result.recommendation() == AnalysisRecommendation.CONFIRMED
                || result.recommendation() == AnalysisRecommendation.IGNORED)
                && retained.isEmpty()) {
            throw new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,
                    "directional model output requires retrieved policy evidence");
        }
        if (result.policyCitations().stream().anyMatch(citation ->
                context.retainedEvidence().stream().noneMatch(evidence -> {
                    var retrieved = evidence.citation();
                    return
                        retrieved.documentId().equals(citation.documentId())
                                && retrieved.documentVersion().equals(citation.version())
                                && retrieved.sectionPath().equals(citation.sourcePath())
                                && evidence.excerpt().equals(citation.excerpt());
                }))) {
            throw new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,
                    "model citation is not an exact match for retrieved policy evidence");
        }
    }

    private String outcome(ErrorCode code) {
        return switch (code) {
            case MODEL_TIMEOUT -> "TIMEOUT";
            case MODEL_RATE_LIMITED -> "RATE_LIMITED";
            default -> "FAILURE";
        };
    }

    private Duration elapsedSince(Instant started) {
        return Duration.between(started, Instant.now());
    }

    private static String loadOutputSchema() {
        try (InputStream input = ModelAnalysisOrchestrator.class.getResourceAsStream(OUTPUT_SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("published analysis output schema is unavailable");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("published analysis output schema could not be read", exception);
        }
    }
}
