package com.finguard.copilot.agent.qualify;

import java.nio.file.*;
import java.util.*;
import java.net.URI;
import java.time.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.copilot.knowledge.domain.*;
import com.finguard.copilot.knowledge.embedding.LoopbackSemanticEmbeddingGateway;
import com.finguard.copilot.knowledge.ingest.DocumentCleaner;
import com.finguard.copilot.knowledge.persistence.*;
import com.finguard.copilot.knowledge.qdrant.*;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Unpaid evaluator: read-only live retrieval, no chat-model or Core client. */
public final class SemanticMarginQualityMain {
    interface Retriever { List<String> retrieve(String query); }
    interface Sink { void save(SemanticMarginQualityMetrics.Outcome outcome) throws Exception; }
    record Development(List<JsonNode> corpus,List<SemanticMarginQualityMetrics.Raw> raw) {}
    record HoldoutExecution(List<SemanticMarginQualityMetrics.Outcome> outcomes,String failureType) {}
    private record Live(KnowledgeRetrievalService retrieval,String indexVersion,Map<String,Object> identity) {}
    private static final String SCOPE="AI-authored bilingual synthetic source relevance with pinned local E5/MySQL/Qdrant retrievePinned; single source per query and one chunk per source; no chat model, Core call, human or production qualification";
    public static void main(String[] args) {
        try {
            if(args.length==3&&args[0].equals("development"))development(Path.of(args[1]),Path.of(args[2]));
            else if(args.length==4&&args[0].equals("holdout"))holdout(Path.of(args[1]),Path.of(args[2]),args[3]);
            else if(args.length==4&&args[0].equals("attest")) {
                SemanticMarginQualityData.attest(Path.of(args[1]),Path.of(args[2]),args[3]);System.out.println("SEMANTIC_MARGIN_ATTESTED");
            }
            else if(args.length==2&&args[0].equals("classpath")) {
                var entries=new ArrayList<Map<String,String>>();for(Path path:SemanticMarginQualityData.classpath())entries.add(Map.of("path",path.toString(),"sha256",SemanticMarginQualityData.pathHash(path)));
                SemanticRagPreparationData.writeNew(Path.of(args[1]),entries);
            } else throw new IllegalArgumentException("Expected development ledger originalData; holdout ledger sealed freezeSha; or classpath output");
        }catch(Exception failure){System.err.println("SEMANTIC_MARGIN_EVALUATION_FAILED "+failure.getClass().getSimpleName());System.exit(2);}
    }
    static List<SemanticMarginQualityMetrics.Outcome> execute(List<SemanticMarginQualityMetrics.Query> cases,Retriever retriever,Sink sink) throws Exception {
        var outcomes=new ArrayList<SemanticMarginQualityMetrics.Outcome>();
        for(var query:cases) {
            SemanticMarginQualityMetrics.Outcome outcome;
            try { outcome=SemanticMarginQualityMetrics.Outcome.executed(query.caseId(),retriever.retrieve(query.query())); }
            catch(RuntimeException failure) { outcome=new SemanticMarginQualityMetrics.Outcome(query.caseId(),"ERROR",List.of(),failure.getClass().getSimpleName()); }
            sink.save(outcome);outcomes.add(outcome);
        }return List.copyOf(outcomes);
    }
    static HoldoutExecution executeHoldout(List<SemanticMarginQualityMetrics.Query> cases,Retriever retriever,Sink sink) {
        var outcomes=new ArrayList<SemanticMarginQualityMetrics.Outcome>();String failureType=null;
        String[] savingCase={null};
        try { execute(cases,retriever,outcome->{
            savingCase[0]=outcome.caseId();sink.save(outcome);outcomes.add(outcome);savingCase[0]=null;
        }); }
        catch(Exception failure){
            failureType=failure.getClass().getSimpleName();
            // A failed CREATE_NEW may have left a partial file. Count the case as an error without retrying that path.
            if(savingCase[0]!=null)outcomes.add(new SemanticMarginQualityMetrics.Outcome(savingCase[0],"ERROR",List.of(),failureType));
        }
        return new HoldoutExecution(List.copyOf(outcomes),failureType);
    }
    static boolean qualityPassed(SemanticMarginQualityMetrics.Metrics metrics,String failureType) { return failureType==null&&metrics.passed(); }
    static Development developmentInputs(Path ledger,Path original,String corpusSha,String devSha,String rawSha) throws Exception {
        var corpus=SemanticMarginQualityData.jsonLines(SemanticMarginQualityData.verifiedBytes(original.resolve("corpus.jsonl"),corpusSha));
        validateCorpus(corpus);
        var cases=SemanticMarginQualityData.cases(SemanticMarginQualityData.jsonLines(SemanticMarginQualityData.verifiedBytes(original.resolve("development.jsonl"),devSha)),SemanticMarginQualityData.SOURCES,false);
        var raw=SemanticMarginQualityData.rawCases(SemanticRagPreparationData.JSON.readTree(SemanticMarginQualityData.verifiedBytes(ledger.resolve("rag-development-candidates.json"),rawSha)),cases,SemanticMarginQualityData.SOURCES);
        return new Development(corpus,raw);
    }
    private static void development(Path ledger,Path original) throws Exception {
        Files.createDirectories(ledger);
        SemanticRagPreparationData.writeNew(ledger.resolve("development-started.json"),Map.of("startedAt",Instant.now(),"holdoutReads",0,"automaticRetries",0));
        var input=developmentInputs(ledger,original,SemanticMarginQualityData.CORPUS_SHA,SemanticMarginQualityData.DEV_SHA,SemanticMarginQualityData.RAW_SHA);
        var chosen=SemanticMarginQualityMetrics.select(input.raw());
        var infrastructure=json(ledger.resolve("rag-live-infrastructure.json"));
        var live=live(infrastructure,input.corpus(),chosen.minimumScore(),chosen.minimumMargin());
        var actual=execute(input.raw().stream().map(SemanticMarginQualityMetrics.Raw::query).toList(),
            query->live.retrieval().retrievePinned(live.indexVersion(),query,1).evidence().stream().map(e->e.citation().documentId()).toList(),
            outcome->SemanticRagPreparationData.writeNew(ledger.resolve("dev-"+outcome.caseId()+".json"),outcome));
        var simulated=input.raw().stream().map(row->SemanticMarginQualityMetrics.simulate(row,chosen.minimumScore(),chosen.minimumMargin())).toList();
        boolean matches=actual.equals(simulated);
        var metrics=SemanticMarginQualityMetrics.evaluate(input.raw().stream().map(SemanticMarginQualityMetrics.Raw::query).toList(),actual);
        var common=new LinkedHashMap<String,Object>();common.put("schemaVersion",1);common.put("algorithm",SemanticMarginQualityMetrics.ALGORITHM);common.put("identity",live.identity());
        common.put("retrieval",Map.of("topK",1,"minimumScore",chosen.minimumScore(),"minimumMargin",chosen.minimumMargin(),"descriptor",live.retrieval().descriptor()));
        common.put("corpusSha256",SemanticMarginQualityData.CORPUS_SHA);common.put("developmentSha256",SemanticMarginQualityData.DEV_SHA);common.put("rawCandidatesSha256",SemanticMarginQualityData.RAW_SHA);
        common.put("scope",SCOPE);common.put("holdoutReads",0);common.put("holdoutExecutions",0);common.put("chatModelCalls",0);common.put("coreCalls",0);
        var results=new LinkedHashMap<>(common);results.put("actual",actual);results.put("simulated",simulated);results.put("simulatorMatchesActual",matches);results.put("metrics",metrics);
        SemanticRagPreparationData.writeNew(ledger.resolve("dev-results.json"),results);
        if(!matches||metrics.pipelineErrors()!=0)throw new IllegalStateException("Actual development retrieval differs from selection simulator");
        common.put("floorGrid",SemanticMarginQualityMetrics.FLOORS);common.put("marginGrid",SemanticMarginQualityMetrics.MARGINS);
        common.put("objective","lexicographic max mean(positiveRecall,unsupportedRejection), precision, higher floor, higher margin");
        common.put("metrics",chosen.metrics());common.put("simulatorMatchesActual",true);common.put("selectedAt",Instant.now());
        common.put("infrastructureSha256",SemanticRagPreparationData.sha256(ledger.resolve("rag-live-infrastructure.json")));
        SemanticRagPreparationData.writeNew(ledger.resolve("selection.json"),common);
        System.out.println("SEMANTIC_MARGIN_DEVELOPMENT_COMPLETE");
    }
    private static void holdout(Path ledger,Path sealed,String anchor) throws Exception {
        var rows=SemanticMarginQualityData.openHoldout(ledger,sealed,anchor);
        var cases=SemanticMarginQualityData.cases(rows,SemanticMarginQualityData.SOURCES,true);
        var freeze=json(ledger.resolve("rag-v2-freeze.json"));var selection=json(ledger.resolve("selection.json"));
        var outcomes=new ArrayList<SemanticMarginQualityMetrics.Outcome>();
        String failureType=null;
        try {
            Path corpusPath=Path.of("tools/agent-evaluation/data/semantic-quality-v1/corpus.jsonl");
            var corpus=SemanticMarginQualityData.jsonLines(SemanticMarginQualityData.verifiedBytes(corpusPath,SemanticMarginQualityData.CORPUS_SHA));validateCorpus(corpus);
            var config=selection.path("retrieval");
            var live=live(json(ledger.resolve("rag-live-infrastructure.json")),corpus,config.path("minimumScore").doubleValue(),config.path("minimumMargin").doubleValue());
            if(!SemanticRagPreparationData.JSON.valueToTree(live.identity()).equals(freeze.path("identity")) || !SemanticRagPreparationData.JSON.valueToTree(live.retrieval().descriptor()).equals(config.path("descriptor")))throw new IllegalArgumentException("Live retrieval identity drift");
            var execution=executeHoldout(cases,query->live.retrieval().retrievePinned(live.indexVersion(),query,1).evidence().stream().map(e->e.citation().documentId()).toList(),
                outcome->SemanticRagPreparationData.writeNew(ledger.resolve("holdout-"+outcome.caseId()+".json"),outcome));
            outcomes.addAll(execution.outcomes());failureType=execution.failureType();
        }catch(Exception failure){failureType=failure.getClass().getSimpleName();}
        var seen=new HashSet<String>();outcomes.forEach(o->seen.add(o.caseId()));
        for(var query:cases)if(!seen.contains(query.caseId())) {
            var outcome=new SemanticMarginQualityMetrics.Outcome(query.caseId(),"NOT_RUN",List.of(),failureType);outcomes.add(outcome);
            SemanticRagPreparationData.writeNew(ledger.resolve("holdout-"+query.caseId()+".json"),outcome);
        }
        var metrics=SemanticMarginQualityMetrics.evaluate(cases,outcomes);
        var report=new LinkedHashMap<String,Object>();report.put("schemaVersion",1);report.put("algorithm",SemanticMarginQualityMetrics.ALGORITHM);report.put("scope",SCOPE);
        report.put("freezeSha256",anchor);report.put("identity",freeze.path("identity"));report.put("retrieval",freeze.path("retrieval"));
        report.put("holdoutExecutions",1);report.put("automaticRetries",0);report.put("chatModelCalls",0);report.put("coreCalls",0);
        report.put("metrics",metrics);report.put("holdoutQualityPassed",qualityPassed(metrics,failureType));report.put("outcomes",outcomes);report.put("completedAt",Instant.now());
        report.put("targets",Map.of("recallAt1",.8,"citationPrecision",.9,"noAnswerAccuracy",.75,"pipelineErrors",0));
        report.put("metricDefinitions",Map.of("recallAt1","relevant returned / 24 positive cases","citationPrecision","relevant returned / all returned, including wrong and unsupported; zero when none returned","noAnswerAccuracy","EXECUTED unsupported cases with zero evidence / 8; ERROR and NOT_RUN never correct","pipelineErrors","ERROR plus NOT_RUN across all 32 cases"));
        if(failureType!=null)report.put("failureType",failureType);
        SemanticRagPreparationData.writeNew(ledger.resolve("holdout-results.json"),report);
        System.out.println("SEMANTIC_MARGIN_HOLDOUT_COMPLETE qualityPassed="+qualityPassed(metrics,failureType));
    }
    private static Live live(JsonNode infrastructure,List<JsonNode> corpus,double floor,double margin) throws Exception {
        if(!SemanticMarginQualityMetrics.FLOORS.contains(floor)||!SemanticMarginQualityMetrics.MARGINS.contains(margin))throw new IllegalArgumentException("Retrieval outside frozen grid");
        String database=text(infrastructure,"database"),index=text(infrastructure,"indexVersion"),version=text(infrastructure,"embeddingVersion"),manifest=text(infrastructure,"embeddingManifestSha256");
        if(!database.matches("fg_sq_[0-9_]+") || !index.equals("sq-primary-"+database.substring(6)) || !version.equals(SemanticRequalificationPreparationData.EMBEDDING_VERSION) || !manifest.equals(SemanticRequalificationPreparationData.EMBEDDING_SHA))throw new IllegalArgumentException("Unexpected live corpus identity");
        for(String[] pair:List.of(new String[]{"COPILOT_EMBEDDING_ENDPOINT","embeddingEndpoint"},new String[]{"COPILOT_QDRANT_ENDPOINT","qdrantEndpoint"},new String[]{"COPILOT_QDRANT_COLLECTION_PREFIX","qdrantPrefix"},new String[]{"COPILOT_EMBEDDING_VERSION","embeddingVersion"},new String[]{"COPILOT_EMBEDDING_MANIFEST_SHA256","embeddingManifestSha256"}))
            if(!required(pair[0]).equals(text(infrastructure,pair[1])))throw new IllegalArgumentException("Live environment identity mismatch");
        URI embedding=loopback(text(infrastructure,"embeddingEndpoint")),qdrant=loopback(text(infrastructure,"qdrantEndpoint"));String prefix=text(infrastructure,"qdrantPrefix");
        if(!prefix.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("Invalid Qdrant prefix");
        String dbUrl=required("COPILOT_DB_URL");if(!dbUrl.startsWith("jdbc:mysql://"))throw new IllegalArgumentException("MySQL required");var dbUri=URI.create(dbUrl.substring(5));
        if(!"127.0.0.1".equals(dbUri.getHost()) || !dbUri.getPath().equals("/"+database) || dbUri.getUserInfo()!=null || dbUri.getFragment()!=null)throw new IllegalArgumentException("Loopback pinned database required");
        var ds=new DriverManagerDataSource(dbUrl,required("COPILOT_DB_USERNAME"),required("COPILOT_DB_PASSWORD"));
        try(var connection=ds.getConnection()){if(!database.equals(connection.getCatalog()))throw new IllegalArgumentException("Database identity mismatch");}
        var jdbc=new JdbcTemplate(ds);var documents=new JdbcKnowledgeDocumentRepository(jdbc,SemanticRagPreparationData.JSON);var indexes=new JdbcKnowledgeIndexRepository(jdbc);
        var published=indexes.findPublished().orElseThrow();if(!index.equals(published.indexVersion())||published.status()!=KnowledgeIndexStatus.PUBLISHED)throw new IllegalStateException("Pinned primary must remain published");
        var membership=indexes.findChunks(index);if(membership.size()!=8)throw new IllegalStateException("Expected eight primary chunks");
        for(var source:corpus) {
            String id=text(source,"id");var chunks=documents.findChunks(id,"v1");
            var canonical=new DocumentCleaner().canonicalize(text(source,"content"));
            if(chunks.size()!=1)throw new IllegalStateException("Single chunk source required");var chunk=chunks.get(0);
            if(!chunk.content().equals(canonical.text()) || !chunk.contentChecksum().equals(canonical.checksum()) || !chunk.sectionPath().equals("Document") || membership.stream().noneMatch(c->c.equals(chunk)))throw new IllegalStateException("SQL corpus checksum/member mismatch");
        }
        var embeddings=new LoopbackSemanticEmbeddingGateway(embedding,version,manifest,384,Duration.ofSeconds(30));
        var vectors=new QdrantVectorStore(new QdrantHttpClient(new QdrantConfiguration(qdrant,prefix,Duration.ofSeconds(5),Duration.ofSeconds(30),384),SemanticRagPreparationData.JSON),prefix);
        var retrieval=new KnowledgeRetrievalService(indexes,embeddings,vectors,version,1,floor,documents,margin);
        var identity=new LinkedHashMap<String,Object>();identity.put("database",database);identity.put("indexVersion",index);identity.put("embeddingVersion",version);identity.put("embeddingManifestSha256",manifest);identity.put("dimensions",384);identity.put("embeddingDescriptor",embeddings.descriptor());
        identity.put("qdrantEndpoint",qdrant.toString());identity.put("qdrantPrefix",prefix);identity.put("embeddingEndpoint",embedding.toString());
        return new Live(retrieval,index,Map.copyOf(identity));
    }
    private static void validateCorpus(List<JsonNode> corpus) {
        var ids=new HashSet<String>();for(var row:corpus)if(!ids.add(text(row,"id"))||!text(row,"version").equals("v1"))throw new IllegalArgumentException("Invalid corpus");
        if(corpus.size()!=8||!ids.equals(SemanticMarginQualityData.SOURCES))throw new IllegalArgumentException("Expected eight source corpus");
    }
    private static JsonNode json(Path path)throws Exception{return SemanticRagPreparationData.JSON.readTree(Files.readAllBytes(path));}
    private static String text(JsonNode row,String key){return SemanticMarginQualityData.text(row,key);}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing local retrieval configuration");return value;}
    private static URI loopback(String value) {
        URI endpoint=URI.create(value);
        if(!"http".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()<1||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null||!Set.of("","/").contains(endpoint.getPath()))throw new IllegalArgumentException("Explicit loopback endpoint required");return endpoint;
    }
}
