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

/** Unpaid corpus-only rebuild. Publication readiness is explicitly not retrieval-quality evidence. */
public final class SemanticRequalificationPreparationMain {
    private static final PublicationThresholds PUBLICATION = new PublicationThresholds(.75,.75,.75);
    private static final String EVALUATOR = "semantic-requalification-v1-readiness-only";
    private static final String SCOPE = "Actual pinned E5/MySQL/Qdrant corpus reconstruction with one retained full-source query per candidate; publication readiness only; no quality evidence, development/holdout replay, chat model or Core call";
    private SemanticRequalificationPreparationMain() {}
    public static void main(String[] args) {
        try {
            if (args.length != 3) throw new IllegalArgumentException("Expected originalPreparation corpus outputDir");
            run(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]));
        } catch (Exception failure) {
            System.err.println("REQUALIFICATION_PREPARATION_FAILED");System.exit(2);
        }
    }
    static void run(Path originalPreparation, Path corpusPath, Path outputDirectory) throws Exception {
        Path output=outputDirectory.toAbsolutePath().normalize();Files.createDirectories(output);
        SemanticRagPreparationData.writeNew(output.resolve("requalification-preparation-started.json"),
            Map.of("startedAt",Instant.now(),"scope",SCOPE,"automaticRetries",0));
        var report=new LinkedHashMap<String,Object>();
        report.put("status","REQUALIFICATION_PREPARATION_FAILED");report.put("actualScope",SCOPE);
        report.put("publicationReadinessOnly",true);report.put("holdoutExecutions",0);report.put("holdoutQualityPassed",false);
        report.put("originalPreparationSha256",SemanticRequalificationPreparationData.ORIGINAL_SHA);
        report.put("corpusSha256",SemanticRequalificationPreparationData.CORPUS_SHA);
        double threshold=SemanticRequalificationPreparationData.MINIMUM_SCORE;report.put("minimumScore",threshold);
        String phase="VERIFY_FROZEN_CORPUS_ONLY_INPUTS";
        try {
            var input=SemanticRequalificationPreparationData.open(originalPreparation,corpusPath);
            var corpus=input.corpus();
            String embeddingVersion=required("COPILOT_EMBEDDING_VERSION");
            String embeddingSha=required("COPILOT_EMBEDDING_MANIFEST_SHA256");
            URI embeddingEndpoint=loopback(required("COPILOT_EMBEDDING_ENDPOINT"));
            if (!SemanticRequalificationPreparationData.EMBEDDING_VERSION.equals(embeddingVersion)
                    || !SemanticRequalificationPreparationData.EMBEDDING_SHA.equals(embeddingSha) || embeddingEndpoint.getPort()!=8766)
                throw new IllegalArgumentException("Exact pinned E5 identity required");
            var embeddings=new LoopbackSemanticEmbeddingGateway(embeddingEndpoint,embeddingVersion,embeddingSha,384,Duration.ofSeconds(30));
            URI qdrantEndpoint=loopback(required("COPILOT_QDRANT_ENDPOINT"));
            String prefix=required("COPILOT_QDRANT_COLLECTION_PREFIX");
            if (!prefix.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid isolated Qdrant prefix");
            var ds=new DriverManagerDataSource(required("COPILOT_DB_URL"),required("COPILOT_DB_USERNAME"),required("COPILOT_DB_PASSWORD"));
            String catalog;
            try (var connection=ds.getConnection()) {
                catalog=connection.getCatalog();
                if(catalog==null || !catalog.matches("fg_sq_[0-9_]+")) throw new IllegalArgumentException("Fresh isolated synthetic database required");
            }
            var jdbc=new JdbcTemplate(ds);
            // Refuse any prior schema before Flyway can alter it. No clean/reset operation exists.
            if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?",Integer.class,catalog)!=0)
                throw new IllegalArgumentException("Preparation requires an empty database before migration");
            Flyway.configure().dataSource(ds).cleanDisabled(true).load().migrate();
            var transactions=new TransactionTemplate(new DataSourceTransactionManager(ds));
            var documents=new JdbcKnowledgeDocumentRepository(jdbc,SemanticRagPreparationData.JSON);
            var indexes=new JdbcKnowledgeIndexRepository(jdbc);
            var vectors=new QdrantVectorStore(new QdrantHttpClient(new QdrantConfiguration(qdrantEndpoint,prefix,
                Duration.ofSeconds(5),Duration.ofSeconds(30),384),SemanticRagPreparationData.JSON),prefix);
            var ingestion=new KnowledgeIngestionService(new DocumentCleaner(),new DocumentChunker(2048,0),
                new KnowledgeAdmissionPolicy(),documents,Instant::now);
            String runId=catalog.substring("fg_sq_".length());String primary="sq-primary-"+runId;String missing="sq-missing-"+runId;
            report.put("indexVersion",primary);report.put("missingPolicyIndexVersion",missing);
            report.put("embeddingVersion",embeddingVersion);report.put("embeddingManifestSha256",embeddingSha);
            report.put("embeddingDescriptor",embeddings.descriptor());report.put("dimensions",384);
            phase="REGISTER_AND_BUILD_PRIMARY";
            var chunks=new ArrayList<KnowledgeChunk>();var byDocument=new LinkedHashMap<String,KnowledgeChunk>();
            for(JsonNode doc:corpus) {
                String id=text(doc,"id");
                var request=new KnowledgeDocumentRequest(id,KnowledgeSourceType.POLICY,text(doc,"title"),"v1",text(doc,"content"));
                transactions.executeWithoutResult(ignored->ingestion.register(request));
                var registered=documents.findChunks(id,"v1");
                if(registered.size()!=1 || !"Document".equals(registered.get(0).sectionPath()))
                    throw new IllegalStateException("Frozen source must produce one Document-section chunk");
                chunks.add(registered.get(0));byDocument.put(id,registered.get(0));
            }
            report.put("corpusChunks",chunks);
            var builder=new KnowledgeIndexBuildService(embeddings,vectors,indexes,embeddingVersion,Instant::now);
            transactions.executeWithoutResult(ignored->builder.build(primary,chunks));
            phase="PRIMARY_PUBLICATION_READINESS_ONLY";
            var evaluator=new KnowledgeIndexEvaluationService(embeddings,vectors,indexes,embeddingVersion,EVALUATOR,1,threshold);
            KnowledgeChunk known=byDocument.get("manual-audit");
            var fixture=new RagCase("REQUALIFICATION-READINESS",known.content(),List.of(known.chunkId()),known.documentId(),known.documentVersion(),List.of());
            var primaryEvaluation=transactions.execute(ignored->evaluator.evaluate(primary,List.of(fixture)));
            report.put("primaryPublicationReadinessEvaluation",primaryEvaluation);report.put("publicationThresholds",PUBLICATION);
            transactions.executeWithoutResult(ignored->new KnowledgeIndexPublicationService(indexes,PUBLICATION).publish(primary,primaryEvaluation));
            report.put("primaryPublished",true);
            phase="MISSING_POLICY_READINESS_ONLY";
            var retained=chunks.stream().filter(chunk->!"synth-usd-limit".equals(chunk.documentId())).toList();
            if(retained.size()!=7) throw new IllegalStateException("Missing-policy fixture requires seven sources");
            transactions.executeWithoutResult(ignored->builder.build(missing,retained));
            var missingEvaluation=transactions.execute(ignored->evaluator.evaluate(missing,List.of(fixture)));
            report.put("missingPolicyEvaluation",missingEvaluation);report.put("missingPolicyPublicationReady",PUBLICATION.accepts(missingEvaluation));
            report.put("readinessFixtures",Map.of("primaryQueries",1,"missingQueries",1,"sourceId","manual-audit","queryForm","retained full source text","qualityEvidence",false));
            if(!PUBLICATION.accepts(missingEvaluation) || indexes.find(primary).orElseThrow().status()!=KnowledgeIndexStatus.PUBLISHED
                    || indexes.find(missing).orElseThrow().status()!=KnowledgeIndexStatus.EVALUATING
                    || !primary.equals(indexes.findPublished().orElseThrow().indexVersion()))
                throw new IllegalStateException("Unexpected readiness/publication state");
            report.put("primaryIndexStatus","PUBLISHED");report.put("missingPolicyIndexStatus","EVALUATING");
            report.put("status","REQUALIFICATION_PREPARED");phase="COMPLETE";
        } catch(Exception failure) {
            report.put("failedPhase",phase);report.put("failureType",failure.getClass().getSimpleName());throw failure;
        } finally {
            report.put("completedAt",Instant.now());SemanticRagPreparationData.writeNew(output.resolve("preparation.json"),report);
        }
        System.out.println("REQUALIFICATION_PREPARATION_STATUS REQUALIFICATION_PREPARED publicationReadinessOnly=true");
    }
    private static String text(JsonNode row,String key) { return SemanticRequalificationPreparationData.text(row,key); }
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
