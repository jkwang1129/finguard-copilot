package com.finguard.copilot.agent.qualify;

import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.JsonNode;

final class SemanticMarginQualityData {
    static final String DEV_SHA="ff3b5e2f1a5cf10ee3de7101e8b89133882e76963f0fb93c0f41406b44b89b01";
    static final String RAW_SHA="c44cd3245681e2e76ca094c455443132f1114e1f9f3f6c3a5df2847c3c5b1d33";
    static final String MANIFEST_SHA="ab056f11b0a781d3011ff0d9e698623adaa66bad608d0af63a65e45cc6632f76";
    static final String HOLDOUT_SHA="9f7a04df08c5c4eb1d32ea124ea175ced02c3a9cd54ac38fc4692892ae15c5bb";
    static final String CORPUS_SHA=SemanticRequalificationPreparationData.CORPUS_SHA;
    static final Set<String> SOURCES=Set.of("account-status","data-retention","euro-review","incident-infrastructure","manual-audit","settlement-reconciliation","support-faq","synth-usd-limit");
    static List<SemanticMarginQualityMetrics.Raw> rawCases(JsonNode root,List<SemanticMarginQualityMetrics.Query> cases,Set<String> sources) {
        if(!root.path("cases").isArray() || root.path("cases").size()!=cases.size()) throw new IllegalArgumentException("Raw case count mismatch");
        var byId=new HashMap<String,SemanticMarginQualityMetrics.Query>();cases.forEach(q->byId.put(q.caseId(),q));
        var result=new ArrayList<SemanticMarginQualityMetrics.Raw>();var ids=new HashSet<String>();
        for(var row:root.path("cases")) {
            String id=text(row,"caseId");var q=byId.get(id);
            if(q==null || !ids.add(id) || !q.query().equals(text(row,"query")) || !q.expected().equals(strings(row,"expectedDocumentIds")) || !q.forbidden().equals(strings(row,"forbiddenDocumentIds")) || !row.path("candidates").isArray()) throw new IllegalArgumentException("Raw query binding mismatch");
            var candidates=new ArrayList<SemanticMarginQualityMetrics.Candidate>();double previous=Double.POSITIVE_INFINITY;int rank=0;
            for(var c:row.path("candidates")) {
                if(!c.path("rank").isIntegralNumber() || c.path("rank").asInt()!=++rank || !c.path("score").isNumber()) throw new IllegalArgumentException("Malformed raw rank");
                var candidate=new SemanticMarginQualityMetrics.Candidate(text(c,"documentId"),c.path("score").doubleValue());
                if(!sources.contains(candidate.documentId()) || candidate.score()>previous) throw new IllegalArgumentException("Invalid raw candidate order/source");
                previous=candidate.score();candidates.add(candidate);
            }
            result.add(new SemanticMarginQualityMetrics.Raw(q,candidates));
        }
        return List.copyOf(result);
    }
    static List<SemanticMarginQualityMetrics.Query> cases(List<JsonNode> rows,Set<String> sources,boolean holdout) {
        int total=holdout?32:12,positive=holdout?24:8;
        if(rows.size()!=total) throw new IllegalArgumentException("Query count mismatch");
        var result=new ArrayList<SemanticMarginQualityMetrics.Query>();var ids=new HashSet<String>();var queries=new HashSet<String>();int positives=0,zh=0,en=0,pzh=0,pen=0;
        for(var row:rows) {
            String id=text(row,"case_id"),query=text(row,"query");var expected=strings(row,"expected_document_ids");var forbidden=strings(row,"forbidden_document_ids");
            var all=new HashSet<>(expected);all.addAll(forbidden);
            if(!(holdout?id.matches("SQ2H-[0-9]{3}"):id.matches("DEV-[0-9]{3}")) || !ids.add(id) || !queries.add(query) || expected.size()>1 || all.size()!=expected.size()+forbidden.size() || !all.equals(sources)) throw new IllegalArgumentException("Invalid query identity/labels");
            String language=holdout?text(row,"language"):"unspecified";
            if(holdout&&!Set.of("zh","en").contains(language)) throw new IllegalArgumentException("Invalid language");
            if(!expected.isEmpty())positives++;
            if(language.equals("zh")){zh++;if(!expected.isEmpty())pzh++;}
            if(language.equals("en")){en++;if(!expected.isEmpty())pen++;}
            result.add(new SemanticMarginQualityMetrics.Query(id,query,expected,forbidden,language));
        }
        if(positives!=positive || (holdout&&(zh!=16||en!=16||pzh!=12||pen!=12))) throw new IllegalArgumentException("Invalid query balance");
        return List.copyOf(result);
    }
    static List<JsonNode> openHoldout(Path ledger,Path sealed,String anchor) throws Exception {
        attest(ledger,sealed,anchor);
        lease(ledger);
        return jsonLines(verifiedBytes(sealed.resolve("holdout.jsonl"),HOLDOUT_SHA));
    }
    static JsonNode attest(Path ledger,Path sealed,String anchor) throws Exception {
        Path root=Path.of("").toAbsolutePath().normalize();
        var freeze=SemanticRagPreparationData.JSON.readTree(verifiedBytes(ledger.resolve("rag-v2-freeze.json"),anchor));
        if(freeze.path("schemaVersion").asInt()!=1 || !SemanticMarginQualityMetrics.ALGORITHM.equals(text(freeze,"algorithm"))) throw new IllegalArgumentException("Invalid freeze identity");
        requireCompleteBindings(root,freeze);
        verifyBindings(root,freeze,classpath());
        verifiedBytes(ledger.resolve("selection.json"),text(freeze,"selectionSha256"));
        verifiedBytes(ledger.resolve("dev-results.json"),text(freeze,"devResultsSha256"));
        verifiedBytes(ledger.resolve("rag-live-infrastructure.json"),text(freeze,"infrastructureSha256"));
        if(!CORPUS_SHA.equals(text(freeze,"corpusSha256")) || !DEV_SHA.equals(text(freeze,"developmentSha256")) || !MANIFEST_SHA.equals(text(freeze,"newManifestSha256")) || !HOLDOUT_SHA.equals(text(freeze,"newHoldoutSha256"))) throw new IllegalArgumentException("Freeze data identity mismatch");
        var selection=SemanticRagPreparationData.JSON.readTree(Files.readAllBytes(ledger.resolve("selection.json")));
        if(!selection.path("identity").equals(freeze.path("identity")) || !selection.path("retrieval").equals(freeze.path("retrieval")) || !SemanticMarginQualityMetrics.ALGORITHM.equals(selection.path("algorithm").asText())) throw new IllegalArgumentException("Selection freeze mismatch");
        validateSelection(selection);
        verifyInfrastructure(selection,SemanticRagPreparationData.JSON.readTree(Files.readAllBytes(ledger.resolve("rag-live-infrastructure.json"))));
        var development=SemanticMarginQualityMain.developmentInputs(ledger,root.resolve("tools/agent-evaluation/data/semantic-quality-v1"),CORPUS_SHA,DEV_SHA,RAW_SHA);
        var selectedAgain=SemanticMarginQualityMetrics.select(development.raw());
        if(selectedAgain.minimumScore()!=selection.path("retrieval").path("minimumScore").doubleValue() || selectedAgain.minimumMargin()!=selection.path("retrieval").path("minimumMargin").doubleValue() || !SemanticRagPreparationData.JSON.valueToTree(selectedAgain.metrics()).equals(selection.path("metrics")))throw new IllegalArgumentException("Development selection is not reproducible");
        var dev=SemanticRagPreparationData.JSON.readTree(Files.readAllBytes(ledger.resolve("dev-results.json")));
        if(!dev.path("simulatorMatchesActual").asBoolean() || dev.path("metrics").path("pipelineErrors").asInt(-1)!=0 || !dev.path("identity").equals(selection.path("identity")) || !dev.path("retrieval").equals(selection.path("retrieval")) || !dev.path("metrics").equals(selection.path("metrics")))throw new IllegalArgumentException("Unverified development execution");
        if(!text(freeze,"infrastructureSha256").equals(text(selection,"infrastructureSha256")))throw new IllegalArgumentException("Infrastructure selection mismatch");
        var manifest=SemanticRagPreparationData.JSON.readTree(verifiedBytes(sealed.resolve("manifest.json"),MANIFEST_SHA));
        if(!HOLDOUT_SHA.equals(manifest.path("files").path("holdout.jsonl").path("sha256").asText())) throw new IllegalArgumentException("Manifest holdout binding mismatch");
        return freeze;
    }
    static void verifyInfrastructure(JsonNode selection,JsonNode infrastructure) {
        for(String field:List.of("database","indexVersion","embeddingVersion","embeddingManifestSha256","qdrantEndpoint","qdrantPrefix","embeddingEndpoint"))
            if(!text(selection.path("identity"),field).equals(text(infrastructure,field)))throw new IllegalArgumentException("Infrastructure identity drift");
    }
    static void validateSelection(JsonNode selection) {
        var retrieval=selection.path("retrieval");var identity=selection.path("identity");
        if(selection.path("schemaVersion").asInt()!=1 || !selection.path("simulatorMatchesActual").asBoolean() || selection.path("holdoutReads").asInt(-1)!=0 || !CORPUS_SHA.equals(selection.path("corpusSha256").asText()) || !DEV_SHA.equals(selection.path("developmentSha256").asText()) || !RAW_SHA.equals(selection.path("rawCandidatesSha256").asText())
            || !retrieval.path("minimumScore").isNumber() || !retrieval.path("minimumMargin").isNumber() || retrieval.path("topK").asInt()!=1 || !SemanticMarginQualityMetrics.FLOORS.contains(retrieval.path("minimumScore").doubleValue()) || !SemanticMarginQualityMetrics.MARGINS.contains(retrieval.path("minimumMargin").doubleValue())
            || !SemanticRequalificationPreparationData.EMBEDDING_VERSION.equals(identity.path("embeddingVersion").asText()) || !SemanticRequalificationPreparationData.EMBEDDING_SHA.equals(identity.path("embeddingManifestSha256").asText()) || identity.path("dimensions").asInt()!=384)throw new IllegalArgumentException("Invalid selected retrieval identity");
        double margin=retrieval.path("minimumMargin").doubleValue();
        var expected=margin==0?Map.of():Map.of("mode","single-source-margin-v1","candidateTopK",2,"topK",1,"minimumScore",retrieval.path("minimumScore").doubleValue(),"minimumMargin",margin,"duplicateSource","abstain","singleCandidate","abstain","candidateSelection","raw-before-filter");
        if(!SemanticRagPreparationData.JSON.valueToTree(expected).equals(retrieval.path("descriptor")))throw new IllegalArgumentException("Retrieval descriptor mismatch");
    }
    private static void requireCompleteBindings(Path root,JsonNode freeze)throws Exception {
        for(String directory:List.of("src/main/java","src/test/java"))try(var paths=Files.walk(root.resolve(directory))) {
            for(Path file:paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".java")).toList())
                if(!freeze.path("sourceHashes").has(root.relativize(file).toString().replace('\\','/')))throw new IllegalArgumentException("Missing source freeze coverage");
        }
        if(!freeze.path("sourceHashes").has("pom.xml"))throw new IllegalArgumentException("Missing build source binding");
        for(String directory:List.of("target/classes","target/test-classes"))try(var paths=Files.walk(root.resolve(directory))) {
            for(Path file:paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".class")).toList())
                if(!freeze.path("artifactHashes").has(root.relativize(file).toString().replace('\\','/')))throw new IllegalArgumentException("Missing actual class freeze coverage");
        }
    }
    static void lease(Path ledger) throws Exception { SemanticRagPreparationData.writeNew(ledger.resolve("holdout-started.json"),Map.of("automaticRetries",0,"startedAt",java.time.Instant.now())); }
    static void verifyBindings(Path root,JsonNode freeze,List<Path> classpath) throws Exception {
        for(String field:List.of("sourceHashes","artifactHashes")) {
            var map=freeze.path(field);if(!map.isObject()||map.isEmpty()) throw new IllegalArgumentException("Missing frozen bindings");
            var iterator=map.fields();while(iterator.hasNext()) {
                var entry=iterator.next();Path file=root.resolve(entry.getKey()).normalize();
                if(Path.of(entry.getKey()).isAbsolute() || !file.startsWith(root.toAbsolutePath().normalize()))throw new IllegalArgumentException("Invalid binding path");
                verifiedBytes(file,entry.getValue().asText());
            }
        }
        var entries=freeze.path("classpathEntries");if(!entries.isArray() || entries.size()!=classpath.size() || classpath.isEmpty()) throw new IllegalArgumentException("Classpath mismatch");
        for(int i=0;i<classpath.size();i++) {
            Path path=classpath.get(i).toAbsolutePath().normalize();var entry=entries.get(i);
            if(!Path.of(text(entry,"path")).isAbsolute() || !path.equals(Path.of(text(entry,"path")).normalize()) || !pathHash(path).equals(text(entry,"sha256"))) throw new IllegalArgumentException("Classpath drift");
        }
    }
    static List<Path> classpath() throws Exception {
        var result=new LinkedHashSet<Path>();var pending=new ArrayDeque<Path>();
        Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator)).map(p->Path.of(p).toAbsolutePath().normalize()).forEach(pending::add);
        while(!pending.isEmpty()) {
            Path path=pending.remove();if(!result.add(path))continue;
            if(Files.isRegularFile(path)&&path.toString().endsWith(".jar"))try(var jar=new java.util.jar.JarFile(path.toFile())) {
                var manifest=jar.getManifest();if(manifest==null)continue;String nested=manifest.getMainAttributes().getValue("Class-Path");
                if(nested!=null)for(String token:nested.trim().split("\\s+"))if(!token.isBlank()) {
                    var uri=path.toUri().resolve(token);if(!uri.getScheme().equals("file"))throw new IllegalArgumentException("Nonlocal classpath");pending.add(Path.of(uri).toAbsolutePath().normalize());
                }
            }
        }return List.copyOf(result);
    }
    static String pathHash(Path path) throws Exception {
        if(!Files.isDirectory(path))return SemanticRagPreparationData.sha256(path);
        var digest=MessageDigest.getInstance("SHA-256");
        try(var files=Files.walk(path)) {
            for(Path file:files.filter(Files::isRegularFile).sorted(Comparator.comparing(p->path.relativize(p).toString().replace('\\','/'))).toList()) {
                if(Files.isSymbolicLink(file))throw new IllegalArgumentException("Symlink classpath");
                digest.update((path.relativize(file).toString().replace('\\','/')+"\0"+SemanticRagPreparationData.sha256(file)+"\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static byte[] verifiedBytes(Path file,String expected) throws Exception {
        if(expected==null || !expected.matches("[a-f0-9]{64}") || Files.isSymbolicLink(file) || !Files.isRegularFile(file))throw new IllegalArgumentException("Invalid frozen file");
        byte[] bytes=Files.readAllBytes(file);
        String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if(!expected.equals(actual))throw new IllegalArgumentException("Frozen file changed");return bytes;
    }
    static List<JsonNode> jsonLines(byte[] bytes) throws Exception {
        var rows=new ArrayList<JsonNode>();
        for(String line:new String(bytes,StandardCharsets.UTF_8).lines().toList()) {
            if(line.isBlank())throw new IllegalArgumentException("Blank query row");var row=SemanticRagPreparationData.JSON.readTree(line);
            if(row==null||!row.isObject())throw new IllegalArgumentException("Invalid query row");rows.add(row);
        }return List.copyOf(rows);
    }
    static String text(JsonNode node,String field) {
        if(!node.path(field).isTextual()||node.path(field).asText().isBlank())throw new IllegalArgumentException("Invalid text field");return node.path(field).asText();
    }
    static List<String> strings(JsonNode node,String field) {
        if(!node.path(field).isArray())throw new IllegalArgumentException("Invalid labels");var result=new ArrayList<String>();
        for(var v:node.path(field)){if(!v.isTextual()||v.asText().isBlank())throw new IllegalArgumentException("Invalid source id");result.add(v.asText());}return List.copyOf(result);
    }
}
