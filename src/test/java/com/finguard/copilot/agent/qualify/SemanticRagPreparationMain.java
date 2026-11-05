package com.finguard.copilot.agent.qualify;

import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.copilot.knowledge.domain.*;
import com.finguard.copilot.knowledge.embedding.LoopbackSemanticEmbeddingGateway;
import com.finguard.copilot.knowledge.index.*;
import com.finguard.copilot.knowledge.ingest.*;
import com.finguard.copilot.knowledge.persistence.*;
import com.finguard.copilot.knowledge.port.*;
import com.finguard.copilot.knowledge.qdrant.*;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Unpaid, local semantic-RAG preparation. This executable has no chat-model or Core client. */
public final class SemanticRagPreparationMain {
    private static final String EVALUATOR = "semantic-quality-v1-development-top1";
    private static final Map<String, Double> QUALITY_TARGETS = Map.of(
        "recallAt1", .80, "citationPrecision", .90, "noAnswerAccuracy", .75);
    private static final PublicationThresholds PUBLICATION = new PublicationThresholds(.75, .75, .75);
    private static final String SCOPE = "AI-authored synthetic source-relevance evaluation with real local E5 CPU "
        + "document/query embeddings, MySQL ingestion/index membership, Qdrant top-1 search and pinned retrieval; "
        + "no main-model calls, Core calls, human calibration, production-quality or factual-answer correctness claim";

    private record QueryCase(String caseId, String query, List<String> expected, List<String> forbidden) {}

    private SemanticRagPreparationMain() {}

    public static void main(String[] args) {
        int exit = 0;
        try {
            if (args.length != 2) throw new IllegalArgumentException("Expected dataDir and outputDir");
            run(Path.of(args[0]), Path.of(args[1]));
        } catch (Exception failure) {
            // Do not print driver exceptions, URLs, passwords or request bodies.
            System.err.println("RAG_PREPARATION_FAILED");
            exit = 2;
        }
        if (exit != 0) System.exit(exit);
    }

    static void run(Path dataDirectory, Path outputDirectory) throws Exception {
        Path output = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(output);
        // A durable lease blocks a second run from silently retrying the same holdout or replacing evidence.
        SemanticRagPreparationData.writeNew(output.resolve("rag-preparation-started.json"),
            Map.of("startedAt", Instant.now(), "scope", SCOPE, "automaticRetries", 0));
        var report = new LinkedHashMap<String, Object>();
        report.put("status", "RAG_PREPARATION_FAILED");
        report.put("actualScope", SCOPE);
        report.put("holdoutQualityTargets", QUALITY_TARGETS);
        report.put("holdoutQualityPassed", false);
        String phase = "VERIFY_DATA_AND_CONFIGURATION";
        try {
            var pack = SemanticRagPreparationData.open(dataDirectory);
            if (!"semantic-quality-v1".equals(pack.manifest().path("pack_id").asText()))
                throw new IllegalArgumentException("Unexpected frozen data pack");
            var corpus = pack.readCorpus();
            if (corpus.size() != 8) throw new IllegalArgumentException("Expected eight frozen corpus documents");
            var sourceIds = new LinkedHashSet<String>();
            for (JsonNode doc : corpus) {
                if (!sourceIds.add(text(doc, "id")) || !"v1".equals(text(doc, "version")))
                    throw new IllegalArgumentException("Invalid frozen source identity");
                text(doc, "content"); text(doc, "title");
            }
            var development = cases(pack.readDevelopment(), sourceIds, "DEV-", 12, 8);
            String embeddingVersion = required("COPILOT_EMBEDDING_VERSION");
            String embeddingSha = required("COPILOT_EMBEDDING_MANIFEST_SHA256");
            var embeddings = new LoopbackSemanticEmbeddingGateway(
                loopback(required("COPILOT_EMBEDDING_ENDPOINT")), embeddingVersion, embeddingSha, 384,
                Duration.ofSeconds(30));
            URI qdrantEndpoint = loopback(required("COPILOT_QDRANT_ENDPOINT"));
            String prefix = required("COPILOT_QDRANT_COLLECTION_PREFIX");
            if (!prefix.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
                throw new IllegalArgumentException("Invalid isolated Qdrant prefix");
            var ds = new DriverManagerDataSource(required("COPILOT_DB_URL"),
                required("COPILOT_DB_USERNAME"), required("COPILOT_DB_PASSWORD"));
            String catalog;
            try (var connection = ds.getConnection()) {
                catalog = connection.getCatalog();
                if (catalog == null || !catalog.matches("fg_sq_[0-9_]+"))
                    throw new IllegalArgumentException("Semantic evaluation requires its own synthetic database");
            }
            Flyway.configure().dataSource(ds).load().migrate();
            var jdbc = new JdbcTemplate(ds);
            for (String table : List.of("knowledge_document", "knowledge_index_version", "analysis_run")) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class) != 0)
                    throw new IllegalArgumentException("Semantic evaluation database already used; no automatic retry");
            }
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
            var documents = new JdbcKnowledgeDocumentRepository(jdbc, SemanticRagPreparationData.JSON);
            var indexes = new JdbcKnowledgeIndexRepository(jdbc);
            var vectors = new QdrantVectorStore(new QdrantHttpClient(new QdrantConfiguration(qdrantEndpoint,
                prefix, Duration.ofSeconds(5), Duration.ofSeconds(30), 384), SemanticRagPreparationData.JSON), prefix);
            var ingestion = new KnowledgeIngestionService(new DocumentCleaner(), new DocumentChunker(2048, 0),
                new KnowledgeAdmissionPolicy(), documents, Instant::now);
            String runId = catalog.substring("fg_sq_".length());
            String primary = "sq-primary-" + runId;
            String missing = "sq-missing-" + runId;
            report.put("indexVersion", primary);
            report.put("missingPolicyIndexVersion", missing);
            report.put("embeddingVersion", embeddingVersion);
            report.put("embeddingManifestSha256", embeddingSha);
            report.put("embeddingDescriptor", embeddings.descriptor());
            report.put("dimensions", 384);
            report.put("dataManifestSha256", pack.manifestSha256());
            report.put("fileSha256", pack.fileSha256());
            report.put("holdoutExecutions", 0);

            phase = "REGISTER_AND_BUILD_PRIMARY";
            var chunks = new ArrayList<KnowledgeChunk>();
            var byDocument = new LinkedHashMap<String, KnowledgeChunk>();
            for (JsonNode doc : corpus) {
                String id = text(doc, "id");
                var request = new KnowledgeDocumentRequest(id, KnowledgeSourceType.POLICY,
                    text(doc, "title"), "v1", text(doc, "content"));
                transactions.executeWithoutResult(ignored -> ingestion.register(request));
                var registered = documents.findChunks(id, "v1");
                if (registered.size() != 1 || !"Document".equals(registered.get(0).sectionPath()))
                    throw new IllegalStateException("Frozen source must produce exactly one Document-section chunk");
                chunks.add(registered.get(0));
                byDocument.put(id, registered.get(0));
            }
            report.put("corpusChunks", chunks);
            var builder = new KnowledgeIndexBuildService(embeddings, vectors, indexes, embeddingVersion, Instant::now);
            transactions.executeWithoutResult(ignored -> builder.build(primary, chunks));

            phase = "DEVELOPMENT_THRESHOLD_SELECTION";
            var developmentRows = new ArrayList<Map<String, Object>>();
            var developmentObservations = new ArrayList<SemanticQualityMetrics.Observation>();
            // These are raw, unfiltered top-1 scores, with exactly one search for every development query.
            for (QueryCase query : development) {
                var results = vectors.search(new VectorSearchQuery(primary,
                    embeddings.embedQuery(query.query(), embeddingVersion), 1));
                if (results.size() > 1) throw new IllegalStateException("Top-1 search returned multiple results");
                String actual = results.isEmpty() ? null : results.get(0).document().metadata().get("documentId");
                if (!results.isEmpty() && !sourceIds.contains(actual))
                    throw new IllegalStateException("Unregistered development result");
                double score = results.isEmpty() ? -1 : results.get(0).score();
                developmentObservations.add(new SemanticQualityMetrics.Observation(query.caseId(), query.expected(), actual, score));
                var row = row(query, actual, score);
                row.put("rawResults", results);
                developmentRows.add(row);
            }
            double threshold = SemanticQualityMetrics.selectThreshold(developmentObservations);
            var developmentMetrics = SemanticQualityMetrics.evaluate(developmentObservations, threshold);
            report.put("minimumScore", threshold);
            report.put("developmentMetrics", developmentMetrics);
            for (var row : developmentRows) {
                row.put("accepted", row.get("actualDocumentId") != null && (double) row.get("score") >= threshold);
            }
            SemanticRagPreparationData.writeNew(output.resolve("development-results.json"), developmentRows);
            phase = "PERSIST_THRESHOLD_FREEZE";
            pack.freeze(output.resolve("threshold-freeze.json"), Map.of(
                "algorithm", "maximize mean(positiveRecallAt1,unsupportedNoAnswerAccuracy); midpoint candidates; higher threshold on ties",
                "algorithmVersion", "SemanticQualityMetrics-v1", "selectedThreshold", threshold,
                "embeddingVersion", embeddingVersion, "embeddingManifestSha256", embeddingSha,
                "dimensions", 384, "topK", 1, "holdoutQualityTargets", QUALITY_TARGETS,
                "frozenAt", Instant.now()));

            phase = "DEVELOPMENT_PUBLICATION_EVALUATION";
            var evaluator = new KnowledgeIndexEvaluationService(embeddings, vectors, indexes, embeddingVersion,
                EVALUATOR, 1, threshold);
            var officialDevelopment = transactions.execute(ignored -> evaluator.evaluate(primary,
                development.stream().map(query -> ragCase(query, byDocument)).toList()));
            report.put("developmentPublicationEvaluation", officialDevelopment);
            report.put("publicationThresholds", PUBLICATION);
            report.put("metricDefinitions", Map.of(
                "qualityRecallAt1", "relevant accepted top-1 retrievals / positive cases",
                "qualityCitationPrecision", "relevant accepted top-1 retrievals / all accepted retrievals; zero if none accepted",
                "qualityNoAnswerAccuracy", "unsupported queries with no accepted retrieval / unsupported cases",
                "builtInPublicationMetric", "separate RagEvaluationResult: recall averages over positive AND unsupported cases; "
                    + "citation scoring with null required citation can score unsupported cases as 1 even on false retrieval"));
            // Thresholds are fixed and never lowered.
            transactions.executeWithoutResult(ignored -> new KnowledgeIndexPublicationService(indexes, PUBLICATION)
                .publish(primary, officialDevelopment));
            report.put("primaryPublished", true);

            phase = "HOLDOUT_PINNED_RETRIEVAL_ONCE";
            // No holdout labels or query text have been parsed before the durable threshold freeze above.
            var holdout = cases(pack.readHoldout(), sourceIds, "HOLD-", 16, 12);
            report.put("holdoutExecutions", 1);
            var retrieval = new KnowledgeRetrievalService(indexes, embeddings, vectors, embeddingVersion, 1, threshold, documents);
            var holdoutRows = new ArrayList<Map<String, Object>>();
            var holdoutObservations = new ArrayList<SemanticQualityMetrics.Observation>();
            for (QueryCase query : holdout) {
                var evidence = retrieval.retrievePinned(primary, query.query(), 1).evidence();
                if (evidence.size() > 1) throw new IllegalStateException("Pinned top-1 retrieval returned multiple results");
                String actual = evidence.isEmpty() ? null : evidence.get(0).citation().documentId();
                double score = evidence.isEmpty() ? -1 : evidence.get(0).citation().score();
                holdoutObservations.add(new SemanticQualityMetrics.Observation(query.caseId(), query.expected(), actual, score));
                var row = row(query, actual, score);
                row.put("accepted", !evidence.isEmpty());
                row.put("evidence", evidence);
                row.put("scoreMeaning", evidence.isEmpty() ? "no accepted hit; -1 is a report sentinel, not a raw candidate score" : "accepted pinned hit score");
                holdoutRows.add(row);
                // Preserve completed cases even if a later case fails; each filename is still CREATE_NEW.
                SemanticRagPreparationData.writeNew(output.resolve("holdout-" + query.caseId() + ".json"), row);
            }
            SemanticRagPreparationData.writeNew(output.resolve("holdout-results.json"), holdoutRows);
            var holdoutMetrics = SemanticQualityMetrics.evaluate(holdoutObservations, threshold);
            report.put("holdoutMetrics", holdoutMetrics);
            report.put("holdoutQualityPassed", holdoutMetrics.recallAt1() >= QUALITY_TARGETS.get("recallAt1")
                && holdoutMetrics.citationPrecision() >= QUALITY_TARGETS.get("citationPrecision")
                && holdoutMetrics.noAnswerAccuracy() >= QUALITY_TARGETS.get("noAnswerAccuracy"));

            phase = "MISSING_POLICY_LIFECYCLE_FIXTURE";
            var retained = chunks.stream().filter(chunk -> !"synth-usd-limit".equals(chunk.documentId())).toList();
            if (retained.size() != 7) throw new IllegalStateException("Missing-policy fixture must contain seven sources");
            transactions.executeWithoutResult(ignored -> builder.build(missing, retained));
            // This is a readiness fixture, not a second benchmark or another holdout pass.
            KnowledgeChunk known = byDocument.get("manual-audit");
            var fixtureCase = new RagCase("MISSING-POLICY-READINESS", known.content(), List.of(known.chunkId()),
                known.documentId(), known.documentVersion(), List.of());
            var missingEvaluation = transactions.execute(ignored -> evaluator.evaluate(missing, List.of(fixtureCase)));
            report.put("missingPolicyEvaluation", missingEvaluation);
            report.put("missingPolicyPublicationReady", PUBLICATION.accepts(missingEvaluation));
            report.put("missingPolicyFixtureScope", "one retained source full-text query; publication readiness only; excludes synth-usd-limit; not quality evidence");
            if (!PUBLICATION.accepts(missingEvaluation))
                throw new IllegalStateException("Missing-policy readiness fixture did not meet fixed publication conditions");
            if (indexes.find(primary).orElseThrow().status() != KnowledgeIndexStatus.PUBLISHED
                    || indexes.find(missing).orElseThrow().status() != KnowledgeIndexStatus.EVALUATING
                    || !primary.equals(indexes.findPublished().orElseThrow().indexVersion()))
                throw new IllegalStateException("Unexpected publication state after preparation");
            report.put("primaryIndexStatus", "PUBLISHED");
            report.put("missingPolicyIndexStatus", "EVALUATING");
            report.put("status", "RAG_EVALUATED");
            phase = "COMPLETE";
        } catch (Exception failure) {
            report.put("failedPhase", phase);
            report.put("failureType", failure.getClass().getSimpleName());
            report.put("failureDetail", "Preparation stopped; no retry or threshold relaxation was performed. Inspect phase and owned local evidence.");
            throw failure;
        } finally {
            report.put("completedAt", Instant.now());
            SemanticRagPreparationData.writeNew(output.resolve("preparation.json"), report);
        }
        System.out.println("RAG_PREPARATION_STATUS RAG_EVALUATED holdoutQualityPassed=" + report.get("holdoutQualityPassed"));
    }

    private static Map<String, Object> row(QueryCase query, String actual, double score) {
        var row = new LinkedHashMap<String, Object>();
        row.put("caseId", query.caseId()); row.put("query", query.query());
        row.put("expectedDocumentIds", query.expected()); row.put("forbiddenDocumentIds", query.forbidden());
        row.put("actualDocumentId", actual); row.put("score", score);
        return row;
    }

    private static RagCase ragCase(QueryCase query, Map<String, KnowledgeChunk> chunks) {
        String citation = query.expected().isEmpty() ? null : query.expected().get(0);
        return new RagCase(query.caseId(), query.query(), query.expected().stream().map(id -> chunks.get(id).chunkId()).toList(),
            citation, citation == null ? null : "v1", query.forbidden().stream().map(id -> chunks.get(id).chunkId()).toList());
    }

    private static List<QueryCase> cases(List<JsonNode> rows, Set<String> sources, String prefix, int total, int positives) {
        if (rows.size() != total) throw new IllegalArgumentException("Frozen query count mismatch");
        var result = new ArrayList<QueryCase>();
        var ids = new HashSet<String>();
        for (var row : rows) {
            String id = text(row, "case_id");
            var expected = stringList(row, "expected_document_ids");
            var forbidden = stringList(row, "forbidden_document_ids");
            var all = new HashSet<>(expected); all.addAll(forbidden);
            if (!id.startsWith(prefix) || !ids.add(id) || expected.size() > 1
                    || !Collections.disjoint(expected, forbidden) || !all.equals(sources)
                    || all.size() != expected.size() + forbidden.size())
                throw new IllegalArgumentException("Frozen relevance labels invalid");
            result.add(new QueryCase(id, text(row, "query"), expected, forbidden));
        }
        if (result.stream().filter(query -> !query.expected().isEmpty()).count() != positives)
            throw new IllegalArgumentException("Frozen positive query count mismatch");
        return List.copyOf(result);
    }

    private static List<String> stringList(JsonNode row, String key) {
        if (!row.path(key).isArray()) throw new IllegalArgumentException("Invalid frozen list field");
        var values = new ArrayList<String>();
        for (var value : row.path(key)) {
            if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Invalid frozen source label");
            values.add(value.asText());
        }
        return List.copyOf(values);
    }
    private static String text(JsonNode row, String key) {
        if (!row.path(key).isTextual() || row.path(key).asText().isBlank())
            throw new IllegalArgumentException("Invalid frozen text field");
        return row.path(key).asText();
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing local preparation configuration");
        return value;
    }
    private static URI loopback(String value) {
        URI endpoint = URI.create(value);
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() < 1 || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !Set.of("", "/").contains(endpoint.getPath()))
            throw new IllegalArgumentException("Preparation service endpoint must be explicit IPv4 loopback");
        return endpoint;
    }
}
