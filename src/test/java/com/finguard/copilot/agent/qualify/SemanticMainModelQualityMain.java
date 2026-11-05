package com.finguard.copilot.agent.qualify;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.copilot.agent.runtime.NativeReviewedPrice;
import com.finguard.copilot.analysis.application.*;
import com.finguard.copilot.analysis.domain.*;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.config.*;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.governance.*;
import com.finguard.copilot.guardrail.*;
import com.finguard.copilot.knowledge.domain.*;
import com.finguard.copilot.knowledge.embedding.LoopbackSemanticEmbeddingGateway;
import com.finguard.copilot.knowledge.index.*;
import com.finguard.copilot.knowledge.persistence.*;
import com.finguard.copilot.knowledge.qdrant.*;
import com.finguard.copilot.knowledge.retrieval.*;
import com.finguard.copilot.model.adapter.openai.CompatibleChatModelGateway;
import com.finguard.copilot.model.config.RealModelConfiguration;
import com.finguard.copilot.model.port.*;
import com.finguard.copilot.model.validation.StructuredAnalysisParser;
import com.finguard.copilot.usage.ModelCallReservations;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import static com.finguard.copilot.agent.qualify.SemanticMainModelQualityData.*;

/** Explicit paid CLI, never a JUnit test. Runs the existing single-call P4 production workflow. */
public final class SemanticMainModelQualityMain {
    private static final BigDecimal CAP = new BigDecimal("1.10");
    private static final String SCOPE = "Eleven AI-authored synthetic project-policy business cases using actual P4 "
        + "ModelAnalysisOrchestrator, semantic top-1 retrieval, adapter, immutable manifest, SQL call/budget ledger, "
        + "Redis admission, schema/policy/fact validators; component quality only; no Core/API/UI process E2E, "
        + "general Agent quality, human expertise or production accuracy certification";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String batch, primary, missing, embeddingVersion;
    private final SnapshotCodec codec = new SnapshotCodec();
    private final JdbcKnowledgeIndexRepository indexes;
    private final KnowledgeRetrievalService retrieval;
    private final BudgetReservations budgets;
    private final ModelAnalysisOrchestrator orchestrator;
    private final CopilotProperties.Model model;
    private final GovernanceProperties governance;
    private final RagEvaluationResult missingEvaluation;
    private int providerAttempts;

    private SemanticMainModelQualityMain(JdbcTemplate jdbc, TransactionTemplate tx, String batch,
            StringRedisTemplate redis, JsonNode preparation) throws Exception {
        this.jdbc = jdbc; this.tx = tx; this.batch = batch;
        primary = text(preparation, "indexVersion"); missing = text(preparation, "missingPolicyIndexVersion");
        embeddingVersion = required("COPILOT_EMBEDDING_VERSION");
        String embeddingSha = required("COPILOT_EMBEDDING_MANIFEST_SHA256");
        if (!embeddingVersion.equals(text(preparation, "embeddingVersion"))
                || !embeddingSha.equals(text(preparation, "embeddingManifestSha256"))
                || !SemanticRequalificationReporting.acceptsPreparation(preparation))
            throw new IllegalArgumentException("Prepared semantic identity mismatch");
        JsonNode score = preparation.path("minimumScore");
        if (!score.isNumber() || !Double.isFinite(score.asDouble()) || score.asDouble() < -1 || score.asDouble() > 1)
            throw new IllegalArgumentException("Invalid frozen retrieval threshold");
        var embeddings = new LoopbackSemanticEmbeddingGateway(loopback(required("COPILOT_EMBEDDING_ENDPOINT")),
            embeddingVersion, embeddingSha, 384, Duration.ofSeconds(10));
        String prefix = required("COPILOT_QDRANT_COLLECTION_PREFIX");
        if (!prefix.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid collection prefix");
        indexes = new JdbcKnowledgeIndexRepository(jdbc);
        var documents = new JdbcKnowledgeDocumentRepository(jdbc, JSON);
        var vectors = new QdrantVectorStore(new QdrantHttpClient(new QdrantConfiguration(
            loopback(required("COPILOT_QDRANT_ENDPOINT")), prefix, Duration.ofSeconds(5), Duration.ofSeconds(30), 384), JSON), prefix);
        retrieval = new KnowledgeRetrievalService(indexes, embeddings, vectors, embeddingVersion, 5, score.asDouble(), documents);
        if (!primary.equals(indexes.findPublished().orElseThrow().indexVersion())
                || indexes.find(missing).orElseThrow().status() != KnowledgeIndexStatus.EVALUATING)
            throw new IllegalArgumentException("Prepared publication state mismatch");
        missingEvaluation = JSON.treeToValue(preparation.path("missingPolicyEvaluation"), RagEvaluationResult.class);
        if (!missing.equals(missingEvaluation.indexVersion()) || !new PublicationThresholds(.75, .75, .75).accepts(missingEvaluation))
            throw new IllegalArgumentException("Prepared missing-policy evaluation mismatch");
        verifyStoredEvaluation(missingEvaluation);
        if (indexes.findChunks(missing).stream().anyMatch(c -> "synth-usd-limit".equals(c.documentId())))
            throw new IllegalArgumentException("Missing-policy candidate contains policy");
        var chunks = documents.findChunks("synth-usd-limit", "v1");
        if (chunks.size() != 1) throw new IllegalArgumentException("Synthetic policy chunk not unique");
        var chunk = chunks.get(0);
        if (!"Document".equals(chunk.sectionPath()) || !CanonicalJson.sha256(chunk.content()).equals(chunk.contentChecksum())
                || indexes.findChunks(primary).stream().noneMatch(c -> c.equals(chunk)))
            throw new IllegalArgumentException("Synthetic reviewed binding mismatch");
        // Bind to the frozen registered corpus, never to model-generated policy conditions.
        boolean frozenChunk = false;
        for (var frozen : preparation.path("corpusChunks")) {
            if (chunk.chunkId().equals(frozen.path("chunkId").asText())
                    && chunk.contentChecksum().equals(frozen.path("contentChecksum").asText())
                    && chunk.content().equals(frozen.path("content").asText())) frozenChunk = true;
        }
        if (!frozenChunk) throw new IllegalArgumentException("Registered policy differs from frozen preparation");
        var binding = new ReviewedPolicyGuard.Binding("synth-usd-limit", "v1", "Document", chunk.contentChecksum());
        var reviewed = new ReviewedPolicyProperties(List.of(new ReviewedPolicyGuard.PolicySet("synthetic-project-usd-v1",
            "SYNTH_LIMIT", "USD", List.of(new ReviewedPolicyGuard.Policy(binding, List.of(
                new ReviewedPolicyGuard.Condition(ReviewedPolicyGuard.Field.AMOUNT, ReviewedPolicyGuard.Operator.GTE, "100.00"),
                new ReviewedPolicyGuard.Condition(ReviewedPolicyGuard.Field.CURRENCY, ReviewedPolicyGuard.Operator.EQ, "USD")),
                AnalysisRecommendation.CONFIRMED, AnalysisRecommendation.IGNORED)))));
        var numeric = new NumericGuardProperties(List.of());
        model = new CopilotProperties.Model("deepseek", "deepseek-flash", URI.create("https://api.deepseek.com"),
            new SecretValue(required("COPILOT_MODEL_API_KEY")), "/chat/completions", 32000, 2000, Duration.ofSeconds(60),
            NativeReviewedPrice.VERSION, "CNY", new BigDecimal("2"), new BigDecimal("8"));
        governance = new GovernanceProperties(new BigDecimal("0.10"), NativeReviewedPrice.FROM, NativeReviewedPrice.UNTIL,
            CAP, CAP, CAP, false);
        NativeReviewedPrice.validate(model, governance, Clock.systemUTC());
        model.validateForRealProfile("real-deepseek");
        var properties = new CopilotProperties(null, new CopilotProperties.Embedding(384), new CopilotProperties.Retrieval(1),
            null, null, model, new CopilotProperties.Prompt("review-analysis-v2"), new CopilotProperties.Budget(32000, 2000, 1));
        budgets = new BudgetReservations(jdbc, tx, new ModelCallReservations(jdbc), Clock.systemUTC());
        var admission = new CallAdmission(() -> budgets, new RedisProviderPermits(redis, 1, 60), model.timeout());
        var manifest = new RunManifestService(new VersionedArtifactStore(jdbc, tx), indexes, properties, numeric, reviewed,
            embeddingVersion, governance, Clock.systemUTC(), admission, embeddings);
        ModelCallPreflight preflight = new ModelCallPreflight() {
            @Override public boolean managesReservation() { return true; }
            @Override public void outcomeUnknown(UUID id) { budgets.markUnknown(id, "MODEL_CALL_OUTCOME_UNKNOWN"); }
            @Override public void outcomeUnknown(UUID id, ModelUsage usage) { budgets.markUnknown(id, "MODEL_CALL_OUTCOME_UNKNOWN", usage); }
            @Override public void afterCall(UUID id) { admission.close(id); }
            @Override public ModelRequest prepare(UUID id, FactSnapshot snapshot, ModelRequest request,
                    List<KnowledgeEvidence> evidence, String indexVersion) {
                NativeReviewedPrice.validate(model, governance, Clock.systemUTC());
                return manifest.prepare(id, snapshot, request, evidence, indexVersion);
            }
        };
        var provider = new CompatibleChatModelGateway(model, RestClient.builder()
            .requestFactory(RealModelConfiguration.modelRequestFactory(model.timeout())), budgets::recordProviderEvidence);
        ModelGateway counted = request -> {
            NativeReviewedPrice.validate(model, governance, Clock.systemUTC());
            UUID id = UUID.fromString(request.requestId());
            if (providerAttempts >= 11 || !budgets.hasReservation(id)
                    || !"RESERVED".equals(jdbc.queryForObject("SELECT status FROM model_budget_reservation WHERE analysis_id=?", String.class, bytes(id))))
                throw RunManifestService.rejected("Component request claim or cap invalid");
            BigDecimal exposure=jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN status='SETTLED' THEN settled_cny ELSE reserved_cny END),0) FROM model_budget_reservation",BigDecimal.class);
            SemanticRequalificationReporting.requireSendWithinBudget(providerAttempts,exposure);
            providerAttempts++;
            return provider.complete(request);
        };
        orchestrator = new ModelAnalysisOrchestrator(counted,
            new ContextAssembler(TokenBudget.phase3Defaults(), TokenEstimator.conservative(), new PromptTemplate("review-analysis-v2")),
            new StructuredAnalysisParser(), model, properties.budget(), retrieval, 1, new NumericThresholdGuard(List.of()),
            new ReviewedPolicyGuard(reviewed.sets()), preflight);
    }

    public static void main(String[] args) {
        try {
            if (args.length != 3) throw new IllegalArgumentException("Expected preparation.json cases.jsonl outputReport.json");
            if (!run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]))) System.exit(2);
        } catch (Exception failure) {
            // Never expose credentials, provider bodies, prompt text or database URLs.
            System.err.println("SEMANTIC_MAIN_QUALITY_FAILED"); System.exit(2);
        }
    }

    static boolean run(Path preparationPath, Path casesPath, Path reportPath) throws Exception {
        Path output = reportPath.toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalArgumentException("Report already exists; no retry");
        Files.createDirectories(output.getParent());
        Path started = output.resolveSibling(output.getFileName() + ".started.json");
        // CREATE_NEW prevents two invocations from billing the same batch or replacing evidence.
        writeNew(started, Map.of("startedAt", Instant.now(), "scope", SCOPE, "automaticRetries", 0));
        var report = new LinkedHashMap<String, Object>();
        report.put("status", "QUALITY_FAILED_COMPONENT"); report.put("rawScope", SCOPE);
        report.put("maxRequests", 11); report.put("caseDenominator", 11); report.put("componentCapCny", CAP.toPlainString());
        report.put("invoiceVerified", false); report.put("knownEstimatedCny", "0.00000000"); report.put("heldCny", "0.00000000");
        report.put("providerClaims", 0); report.put("providerAttempts", 0); report.put("automaticRetries", 0);
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= 11; i++) {
            var row = new LinkedHashMap<String, Object>(); row.put("case_id", "QW-%03d".formatted(i));
            row.put("execution", "NOT_RUN"); row.put("protocolPassed", false); row.put("qualityPassed", false);
            row.put("validationStage", "UNKNOWN"); row.put("controlledFailureCode", "NOT_RUN"); row.put("providerAttempts", 0);
            row.put("expectedRecommendation", null); row.put("actualRecommendation", null); row.put("validatedResult", null);
            row.put("usageVerified", false); row.put("knownEstimatedCny", "0.00000000"); row.put("heldCny", "0.00000000");
            row.put("latencyMillis", 0); rows.add(row);
        }
        report.put("rows", rows);
        SemanticMainModelQualityMain runner = null;
        LettuceConnectionFactory redisFactory = null;
        String phase = "FROZEN_INPUT_AND_APPROVAL";
        try {
            String casesSha = approvedCasesSha(System.getenv());
            var cases = openCases(casesPath, casesSha);
            String prepSha = System.getenv("SEMANTIC_QUALITY_PREPARATION_SHA256");
            if (prepSha != null) verifyHash(preparationPath, prepSha);
            String actualPrepSha = sha256(preparationPath);
            JsonNode preparation = JSON.readTree(Files.readAllBytes(preparationPath));
            if (!actualPrepSha.equals(sha256(preparationPath))) throw new IllegalArgumentException("Preparation changed during loading");
            report.put("casesSha256", casesSha); report.put("preparationSha256", actualPrepSha);
            for (int i = 0; i < cases.size(); i++) rows.get(i).put("expectedRecommendation", cases.get(i).expected().name());
            phase = "OWNED_UNUSED_DATABASE";
            var ds = new DriverManagerDataSource(required("COPILOT_DB_URL"), required("COPILOT_DB_USERNAME"), required("COPILOT_DB_PASSWORD"));
            String catalog;
            try (var connection = ds.getConnection()) {
                catalog = connection.getCatalog();
                if (catalog == null || !catalog.matches("fg_sq_[0-9_]+"))
                    throw new IllegalArgumentException("An isolated synthetic database is required");
            }
            var jdbc = new JdbcTemplate(ds);
            for (String table : List.of("analysis_run", "analysis_input_snapshot", "analysis_result", "model_call_reservation",
                    "model_run_manifest", "model_budget_reservation", "agent_model_call"))
                if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class) != 0)
                    throw new IllegalArgumentException("Database already used; no automatic retry");
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            phase = "LOCAL_ADMISSION_AND_PRODUCTION_COMPONENTS";
            String port = required("SEMANTIC_QUALITY_REDIS_PORT");
            if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535)
                throw new IllegalArgumentException("Invalid owned Redis port");
            var redisConfig = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(port));
            redisConfig.setPassword(required("SEMANTIC_QUALITY_REDIS_PASSWORD"));
            redisFactory = new LettuceConnectionFactory(redisConfig); redisFactory.afterPropertiesSet(); redisFactory.start();
            var redis = new StringRedisTemplate(redisFactory); redis.afterPropertiesSet();
            try (var connection = redisFactory.getConnection()) {
                if (!"PONG".equals(connection.ping())) throw new IllegalArgumentException("Owned Redis unavailable");
            }
            runner = new SemanticMainModelQualityMain(jdbc, tx, catalog, redis, preparation);
            report.put("indexVersion", runner.primary); report.put("missingPolicyIndexVersion", runner.missing);
            report.put("embeddingVersion", runner.embeddingVersion); report.put("minimumScore", preparation.path("minimumScore"));
            for (int i = 0; i < cases.size(); i++) {
                rows.get(i).put("contextIndexVersion", "missing-policy".equals(cases.get(i).publication()) ? runner.missing : runner.primary);
                rows.get(i).put("embeddingVersion", runner.embeddingVersion);
            }
            var lifecycle = new LinkedHashMap<String, Object>(); report.put("lifecycleChecks", lifecycle);
            boolean stopped = false;
            for (int i = 0; i < cases.size(); i++) {
                phase = "CASE_" + cases.get(i).caseId();
                if (stopped) { rows.get(i).put("controlledFailureCode", "BATCH_STOPPED_UNCERTAIN_OR_INTEGRITY_FAILURE"); continue; }
                boolean switched = false;
                try {
                    if (i == 10) {
                        runner.publishMissing(lifecycle); switched = true;
                    }
                    stopped = !runner.runCase(cases.get(i), rows.get(i));
                } finally {
                    if (switched) runner.restorePrimary(lifecycle);
                    writeNew(output.resolveSibling(output.getFileName() + "." + cases.get(i).caseId() + ".json"), rows.get(i));
                }
            }
            runner.account(report);
            boolean lifecyclePassed = Boolean.TRUE.equals(lifecycle.get("missingPublished"))
                && Boolean.TRUE.equals(lifecycle.get("retiredPrimaryPinnedRetrievalRefused"))
                && Boolean.TRUE.equals(lifecycle.get("primaryRestored"));
            report.put("status", rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("qualityPassed")))
                && lifecyclePassed && Boolean.TRUE.equals(report.get("accountingIntegrityPassed"))
                ? "QUALITY_PASSED" : "QUALITY_FAILED_COMPONENT");
            phase = "COMPLETE";
        } catch (Exception failure) {
            report.put("failedPhase", phase); report.put("controlledFailureCode", code(failure));
            if (runner != null) {
                report.put("providerAttempts", runner.providerAttempts);
                try { runner.account(report); }
                catch (Exception accountingFailure) {
                    report.put("accountingIntegrityPassed", false); report.put("accountingUnavailable", true);
                    report.put("knownEstimatedCny", null); report.put("heldCny", null); report.put("providerClaims", null);
                }
            }
        } finally {
            if (redisFactory != null) {
                try { redisFactory.destroy(); }
                catch (RuntimeException cleanupFailure) { report.put("redisCleanupFailed", true); }
            }
            report.put("completedAt", Instant.now());
            report.put("qualityPassedCount", rows.stream().filter(row -> Boolean.TRUE.equals(row.get("qualityPassed"))).count());
            writeNew(output, report);
        }
        System.out.println("SEMANTIC_MAIN_QUALITY_STATUS " + report.get("status"));
        return "QUALITY_PASSED".equals(report.get("status"));
    }

    private FactSnapshot create(SemanticMainModelQualityData.Case spec, UUID id) {
        var now = Instant.now();
        var facts = new ReviewTaskContext(new ReviewTaskContext.ReviewTask(42, 0, "PENDING", spec.ruleCode(), spec.reasonCode(), now),
            new ReviewTaskContext.Transaction(2, 3, new BigDecimal(spec.amount()), spec.currency(), now, "CSV_IMPORT"),
            new ReviewTaskContext.Reconciliation("SUSPICIOUS", "NONE", "REVIEW"), spec.riskFacts(),
            new ReviewTaskContext.AccountSummary("ACTIVE", spec.account()), now);
        FactSnapshot snapshot = codec.capture(facts);
        tx.executeWithoutResult(ignored -> {
            jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED','semantic-quality-reviewer',42,0,?,?)",
                bytes(id), batch + ":" + spec.caseId(), snapshot.sha256());
            jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) VALUES(?,1,?,?,?,?)",
                bytes(id), new String(codec.canonicalBytes(snapshot), StandardCharsets.UTF_8), snapshot.sha256(),
                Timestamp.from(snapshot.capturedAt()), Timestamp.from(snapshot.expiresAt()));
        });
        return snapshot;
    }

    /** False means an uncertain cost or an accounting integrity problem requires stopping the batch. */
    private boolean runCase(SemanticMainModelQualityData.Case spec, Map<String, Object> row) {
        UUID id = UUID.nameUUIDFromBytes((batch + ":" + spec.caseId()).getBytes(StandardCharsets.UTF_8));
        row.put("analysisId", id.toString()); row.put("scenario", "p4-primary"); row.put("publication", spec.publication());
        row.put("inputAmount", spec.amount()); row.put("contextIndexVersion", "missing-policy".equals(spec.publication()) ? missing : primary);
        row.put("embeddingVersion", embeddingVersion); row.put("invoiceVerified", false);
        int before = providerAttempts; Instant start = Instant.now(); ModelUsage usage = null;
        boolean settled = false, safeToContinue = true, finalFacts = false;
        try {
            String expectedIndex = "missing-policy".equals(spec.publication()) ? missing : primary;
            if (!expectedIndex.equals(indexes.findPublished().orElseThrow().indexVersion())) throw RunManifestService.rejected("Publication changed before case");
            var snapshot = create(spec, id); row.put("snapshotSha256", HexFormat.of().formatHex(snapshot.sha256()));
            AnalysisExecution execution = orchestrator.execute(id, snapshot);
            usage = execution.usage();
            settled = budgets.settle(id, usage);
            if (!settled) throw RunManifestService.rejected("Provider usage could not be settled");
            finalFacts = true;
            new FactConsistencyValidator().validate(id, snapshot, execution.result());
            finalFacts = false;
            // execute already ran schema, citation and trusted reviewed-policy checks. Never save raw candidates.
            AnalysisResult validated = execution.result();
            tx.executeWithoutResult(ignored -> {
                jdbc.update("INSERT INTO analysis_result(analysis_id,schema_version,recommendation,result_json) VALUES(?,1,?,?)",
                    bytes(id), validated.recommendation().name(), CanonicalJson.encode(validated));
                jdbc.update("UPDATE analysis_run SET status='SUCCEEDED',failure_code=NULL WHERE id=?", bytes(id));
            });
            row.put("validatedResult", validated); row.put("actualRecommendation", validated.recommendation().name());
            row.put("validatedAmounts", validated.businessEvidence().stream().map(e -> e.amount().toPlainString()).toList());
            row.put("protocolPassed", true);
            row.put("qualityPassed", qualityPassed(true, spec.expected(), validated.recommendation()));
            row.put("controlledFailureCode", null);
        } catch (Exception failure) {
            if (failure instanceof ModelInvocationException invocation) usage = invocation.usage();
            row.put("validationStage", SemanticRequalificationReporting.failureStage(failure, finalFacts));
            row.put("controlledFailureCode", code(failure));
            if (budgets.hasReservation(id)) {
                try {
                    if (!settled && usage != null && usage.inputTokens() > 0) settled = budgets.settle(id, usage);
                    if (!settled) budgets.markUnknown(id, "SEMANTIC_QUALITY_OUTCOME_UNVERIFIED", usage);
                } catch (RuntimeException accountingFailure) {
                    safeToContinue = false;
                    try { budgets.markUnknown(id, "SEMANTIC_QUALITY_ACCOUNTING_FAILURE", usage); } catch (RuntimeException ignored) { /* Report held rows below. */ }
                }
            }
            try {
                tx.executeWithoutResult(ignored -> jdbc.update("UPDATE analysis_run SET status='FAILED',failure_code=? WHERE id=?", code(failure), bytes(id)));
            } catch (RuntimeException persistenceFailure) { safeToContinue = false; }
        } finally {
            row.put("execution", providerAttempts > before ? "EXECUTED" : "PRE_SEND_REJECTED");
            row.put("providerAttempts", providerAttempts - before); row.put("latencyMillis", Duration.between(start, Instant.now()).toMillis());
            if (usage != null) row.put("reportedUsage", Map.of("inputTokens", usage.inputTokens(), "outputTokens", usage.outputTokens(), "latencyMillis", usage.latency().toMillis()));
            var reservations = jdbc.queryForList("SELECT status,reserved_cny,settled_cny,input_tokens,output_tokens FROM model_budget_reservation WHERE analysis_id=?", bytes(id));
            row.put("budgetReservations", reservations);
            if (!reservations.isEmpty()) {
                String state = (String) reservations.get(0).get("status");
                row.put("usageVerified", "SETTLED".equals(state));
                row.put("knownEstimatedCny", reservations.get(0).get("settled_cny"));
                row.put("heldCny", Set.of("RESERVED", "UNKNOWN_HELD").contains(state) ? reservations.get(0).get("reserved_cny") : BigDecimal.ZERO);
                if (Set.of("RESERVED", "UNKNOWN_HELD").contains(state)) safeToContinue = false;
            } else { row.put("usageVerified", false); row.put("heldCny", BigDecimal.ZERO); }
            var manifests = jdbc.queryForList("SELECT manifest_sha256,manifest_json FROM model_run_manifest WHERE analysis_id=?", bytes(id));
            if (!manifests.isEmpty()) {
                row.put("contextManifestSha256", manifests.get(0).get("manifest_sha256"));
                try {
                    JsonNode manifest = JSON.readTree((String) manifests.get(0).get("manifest_json"));
                    row.put("contextIndexSha256", manifest.path("index").path("sha256").asText());
                    row.put("contextEmbeddingSha256", manifest.path("embedding").path("sha256").asText());
                } catch (Exception invalid) { safeToContinue = false; }
            }
        }
        return safeToContinue;
    }

    private void publishMissing(Map<String, Object> checks) {
        tx.executeWithoutResult(ignored -> new KnowledgeIndexPublicationService(indexes, new PublicationThresholds(.75, .75, .75)).publish(missing, missingEvaluation));
        checks.put("missingPublished", true);
        try {
            if (indexes.find(primary).orElseThrow().status() != KnowledgeIndexStatus.RETIRED
                    || !missing.equals(indexes.findPublished().orElseThrow().indexVersion()))
                throw new IllegalStateException("Publication did not retire primary");
            checks.put("primaryRetired", true);
            boolean refused = false;
            try { retrieval.retrievePinned(primary, "USD amount threshold policy review", 1); }
            catch (KnowledgeRetrievalException expected) { refused = true; }
            checks.put("retiredPrimaryPinnedRetrievalRefused", refused);
            if (!refused) throw new IllegalStateException("Retired primary remained retrievable");
        } catch (RuntimeException failure) {
            restorePrimary(checks);
            throw failure;
        }
    }

    private void restorePrimary(Map<String, Object> checks) {
        tx.executeWithoutResult(ignored -> indexes.restoreAndPublish(primary, missing));
        checks.put("primaryRestored", primary.equals(indexes.findPublished().orElseThrow().indexVersion())
            && indexes.find(missing).orElseThrow().status() == KnowledgeIndexStatus.RETIRED);
    }

    private void verifyStoredEvaluation(RagEvaluationResult evaluation) {
        var stored = jdbc.queryForMap("SELECT evaluation_recall_at_k,evaluation_citation_precision,evaluation_no_answer_correctness,evaluator_version,evaluated_at FROM knowledge_index_version WHERE index_version=?", evaluation.indexVersion());
        if (stored.get("evaluated_at") == null || !evaluation.evaluatorVersion().equals(stored.get("evaluator_version"))
                || Double.compare(((Number) stored.get("evaluation_recall_at_k")).doubleValue(), evaluation.recallAtK()) != 0
                || Double.compare(((Number) stored.get("evaluation_citation_precision")).doubleValue(), evaluation.citationPrecision()) != 0
                || Double.compare(((Number) stored.get("evaluation_no_answer_correctness")).doubleValue(), evaluation.noAnswerCorrectness()) != 0)
            throw new IllegalArgumentException("Stored missing-policy evaluation mismatch");
    }

    private void account(Map<String, Object> report) {
        var entries = jdbc.queryForList("SELECT status,reserved_cny,settled_cny,input_tokens,output_tokens FROM model_budget_reservation");
        BigDecimal known = BigDecimal.ZERO, held = BigDecimal.ZERO;
        var states = new TreeMap<String, Integer>();
        long input = 0, output = 0;
        for (var entry : entries) {
            String state = (String) entry.get("status"); states.merge(state, 1, Integer::sum);
            if ("SETTLED".equals(state)) {
                known = known.add((BigDecimal) entry.get("settled_cny"));
                input += ((Number) entry.get("input_tokens")).longValue(); output += ((Number) entry.get("output_tokens")).longValue();
            } else if (Set.of("RESERVED", "UNKNOWN_HELD").contains(state)) held = held.add((BigDecimal) entry.get("reserved_cny"));
        }
        int claims = jdbc.queryForObject("SELECT COUNT(*) FROM model_call_reservation", Integer.class);
        report.put("knownEstimatedCny", known.toPlainString()); report.put("heldCny", held.toPlainString());
        report.put("providerAttempts", providerAttempts); report.put("providerClaims", claims);
        report.put("budgetReservationStates", states); report.put("budgetReservations", entries);
        report.put("verifiedInputTokens", input); report.put("verifiedOutputTokens", output);
        report.put("accountingIntegrityPassed", held.signum() == 0 && claims == providerAttempts
            && claims == entries.size() && providerAttempts <= 11 && known.add(held).compareTo(CAP) <= 0);
    }

    private static byte[] bytes(UUID id) { return JdbcAnalysisRunRepository.uuidBytes(id); }
    private static String code(Exception failure) {
        if (failure instanceof ModelInvocationException invocation) return invocation.errorCode().name();
        if (failure instanceof CopilotException controlled) return controlled.code().name();
        return "COMPONENT_" + failure.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required configuration: " + name);
        return value;
    }
    private static URI loopback(String endpoint) {
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Local endpoint required");
        return uri;
    }
}
