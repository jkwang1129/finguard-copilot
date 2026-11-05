package com.finguard.copilot.agent.qualify;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemanticMarginQualityTest {
    @TempDir Path temp;
    private SemanticMarginQualityMetrics.Query query(String id, boolean positive) {
        return new SemanticMarginQualityMetrics.Query(id,"query "+id,positive?List.of("a"):List.of(),positive?List.of("b"):List.of("a","b"),"en");
    }
    private SemanticMarginQualityMetrics.Raw raw(String id, boolean positive, double first, double second) {
        return new SemanticMarginQualityMetrics.Raw(query(id,positive),List.of(
            new SemanticMarginQualityMetrics.Candidate("a",first),new SemanticMarginQualityMetrics.Candidate("b",second)));
    }
    @Test void selectsFromDevelopmentGridUsingBalancedObjectiveThenPrecision() {
        var selected=SemanticMarginQualityMetrics.select(List.of(raw("p",true,.84,.82),raw("n",false,.83,.829)));
        assertThat(selected.minimumScore()).isEqualTo(.825);
        assertThat(selected.minimumMargin()).isEqualTo(.02);
        assertThat(selected.metrics().recallAt1()).isEqualTo(1);
        assertThat(selected.metrics().citationPrecision()).isEqualTo(1);
        assertThat(selected.metrics().noAnswerAccuracy()).isEqualTo(1);
    }
    @Test void breaksIdenticalQualityTiesByHigherFloorThenHigherMargin() {
        var selected=SemanticMarginQualityMetrics.select(List.of(raw("p",true,.99,.80),raw("n",false,.74,.73)));
        assertThat(selected.minimumScore()).isEqualTo(.875);
        assertThat(selected.minimumMargin()).isEqualTo(.05);
    }
    @Test void missingAndDuplicateRunnerAbstainOnlyInMarginMode() {
        var one=new SemanticMarginQualityMetrics.Raw(query("p",true),List.of(new SemanticMarginQualityMetrics.Candidate("a",.9)));
        var duplicate=new SemanticMarginQualityMetrics.Raw(query("p",true),List.of(new SemanticMarginQualityMetrics.Candidate("a",.9),new SemanticMarginQualityMetrics.Candidate("a",.8)));
        assertThat(SemanticMarginQualityMetrics.simulate(one,.8,.01).documentIds()).isEmpty();
        assertThat(SemanticMarginQualityMetrics.simulate(duplicate,.8,.01).documentIds()).isEmpty();
        assertThat(SemanticMarginQualityMetrics.simulate(one,.8,0).documentIds()).containsExactly("a");
    }
    @Test void errorsAndNotRunRemainInDenominatorAndNeverCountAsCorrectAbstention() {
        var cases=List.of(query("p",true),query("n1",false),query("n2",false));
        var metrics=SemanticMarginQualityMetrics.evaluate(cases,List.of(
            SemanticMarginQualityMetrics.Outcome.executed("p",List.of("a")),
            new SemanticMarginQualityMetrics.Outcome("n1","ERROR",List.of(),"IllegalStateException")));
        assertThat(metrics.total()).isEqualTo(3);
        assertThat(metrics.pipelineErrors()).isEqualTo(2);
        assertThat(metrics.noAnswerAccuracy()).isZero();
        assertThat(metrics.passed()).isFalse();
    }
    @Test void allAbstentionFailsPrecisionAndRecallEvenWhenNegativesCorrect() {
        var cases=List.of(query("p",true),query("n",false));
        var metrics=SemanticMarginQualityMetrics.evaluate(cases,List.of(
            SemanticMarginQualityMetrics.Outcome.executed("p",List.of()),SemanticMarginQualityMetrics.Outcome.executed("n",List.of())));
        assertThat(metrics.citationPrecision()).isZero();assertThat(metrics.recallAt1()).isZero();
        assertThat(metrics.noAnswerAccuracy()).isEqualTo(1);assertThat(metrics.passed()).isFalse();
    }
    @Test void wrongAndNegativeHitsReducePrecision() {
        var cases=List.of(query("p",true),query("wrong",true),query("n",false));
        var metrics=SemanticMarginQualityMetrics.evaluate(cases,List.of(
            SemanticMarginQualityMetrics.Outcome.executed("p",List.of("a")),
            SemanticMarginQualityMetrics.Outcome.executed("wrong",List.of("b")),
            SemanticMarginQualityMetrics.Outcome.executed("n",List.of("a"))));
        assertThat(metrics.citationPrecision()).isCloseTo(1d/3,within(1e-12));
        assertThat(metrics.recallAt1()).isEqualTo(.5);
    }
    @Test void rejectsUnidentifiedOrRepeatedOutcomes() {
        var o=SemanticMarginQualityMetrics.Outcome.executed("p",List.of());
        assertThatThrownBy(()->SemanticMarginQualityMetrics.evaluate(List.of(query("p",true)),List.of(o,o))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SemanticMarginQualityMetrics.evaluate(List.of(query("n",false)),List.of(o))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedRawCandidateOrderAndScoresAreRejected() throws Exception {
        String row="{\"cases\":[{\"caseId\":\"p\",\"query\":\"query p\",\"expectedDocumentIds\":[\"a\"],\"forbiddenDocumentIds\":[\"b\"],\"candidates\":[{\"rank\":1,\"score\":0.8,\"documentId\":\"a\"},{\"rank\":2,\"score\":0.9,\"documentId\":\"b\"}]}]}";
        var json=SemanticRagPreparationData.JSON.readTree(row);
        assertThatThrownBy(()->SemanticMarginQualityData.rawCases(json,List.of(query("p",true)),Set.of("a","b"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SemanticMarginQualityMetrics.Candidate("a",Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validatesCountsLanguagesSourcePartitionsAndUniqueIds() throws Exception {
        var rows=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for(int i=1;i<=32;i++) rows.add(SemanticRagPreparationData.JSON.readTree("{\"case_id\":\"SQ2H-"+String.format("%03d",i)+"\",\"query\":\"synthetic "+i+"\",\"language\":\""+(i%2==0?"zh":"en")+"\",\"expected_document_ids\":"+(i<=24?"[\"a\"]":"[]")+",\"forbidden_document_ids\":"+(i<=24?"[\"b\"]":"[\"a\",\"b\"]")+"}"));
        assertThat(SemanticMarginQualityData.cases(rows,Set.of("a","b"),true)).hasSize(32);
        rows.set(31,rows.get(0));
        assertThatThrownBy(()->SemanticMarginQualityData.cases(rows,Set.of("a","b"),true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void holdoutCannotBeReadBeforeBindingsVerifyAndLeaseIsExclusive() throws Exception {
        Path sealed=temp.resolve("sealed");Files.createDirectories(sealed);
        Files.writeString(sealed.resolve("holdout.jsonl"),"not valid json");
        assertThatThrownBy(()->SemanticMarginQualityData.openHoldout(temp,sealed,"0".repeat(64))).isInstanceOf(Exception.class);
        assertThat(Files.exists(temp.resolve("holdout-started.json"))).isFalse();
        SemanticMarginQualityData.lease(temp);
        assertThatThrownBy(()->SemanticMarginQualityData.lease(temp)).isInstanceOf(FileAlreadyExistsException.class);
    }
    @Test void verifiesHashAndRejectsSourceArtifactOrClasspathDrift() throws Exception {
        Path source=temp.resolve("source.java"),artifact=temp.resolve("artifact.class"),cp=temp.resolve("cp.jar");
        Files.writeString(source,"source");Files.writeString(artifact,"artifact");Files.writeString(cp,"cp");
        var freeze=SemanticRagPreparationData.JSON.valueToTree(Map.of("sourceHashes",Map.of("source.java",SemanticRagPreparationData.sha256(source)),"artifactHashes",Map.of("artifact.class",SemanticRagPreparationData.sha256(artifact)),"classpathEntries",List.of(Map.of("path",cp.toString(),"sha256",SemanticMarginQualityData.pathHash(cp)))));
        SemanticMarginQualityData.verifyBindings(temp,freeze,List.of(cp));
        Files.writeString(source,"drift");assertThatThrownBy(()->SemanticMarginQualityData.verifyBindings(temp,freeze,List.of(cp))).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(source,"source");Files.writeString(artifact,"drift");assertThatThrownBy(()->SemanticMarginQualityData.verifyBindings(temp,freeze,List.of(cp))).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(artifact,"artifact");Files.writeString(cp,"drift");assertThatThrownBy(()->SemanticMarginQualityData.verifyBindings(temp,freeze,List.of(cp))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void verifiedBytesRejectChangedHashesAndWritesNeverOverwrite() throws Exception {
        Path file=temp.resolve("data");Files.writeString(file,"synthetic");
        assertThatThrownBy(()->SemanticMarginQualityData.verifiedBytes(file,"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        SemanticRagPreparationData.writeNew(temp.resolve("out.json"),Map.of("ok",true));
        assertThatThrownBy(()->SemanticRagPreparationData.writeNew(temp.resolve("out.json"),Map.of("ok",false))).isInstanceOf(FileAlreadyExistsException.class);
    }
    @Test void driverSendsOnlyQueryAndCallsEachCaseOncePreservingFailures() throws Exception {
        var cases=List.of(query("p",true),query("n",false));var seen=new ArrayList<String>();var saved=new ArrayList<SemanticMarginQualityMetrics.Outcome>();
        var outcomes=SemanticMarginQualityMain.execute(cases,text->{seen.add(text);if(text.equals("query n"))throw new IllegalStateException("sensitive exception");return List.of("a");},saved::add);
        assertThat(seen).containsExactly("query p","query n");
        assertThat(saved).hasSize(2);assertThat(outcomes.get(1).state()).isEqualTo("ERROR");
        assertThat(outcomes.get(1).failureType()).isEqualTo("IllegalStateException");
        assertThat(outcomes.toString()).doesNotContain("sensitive exception");
        assertThat(SemanticMarginQualityMetrics.evaluate(cases,outcomes).noAnswerAccuracy()).isZero();
    }
    @Test void developmentLoadingIgnoresOldManifestAndHoldout() throws Exception {
        Path original=temp.resolve("original"),ledger=temp.resolve("ledger");Files.createDirectories(original);Files.createDirectories(ledger);
        Files.writeString(original.resolve("holdout.jsonl"),"DO NOT PARSE");Files.writeString(original.resolve("manifest.json"),"DO NOT PARSE");
        var sources=SemanticMarginQualityData.SOURCES.stream().sorted().toList();
        var corpus=new ArrayList<String>();for(String id:sources)corpus.add(SemanticRagPreparationData.JSON.writeValueAsString(Map.of("id",id,"version","v1","title","t","content","body "+id)));
        Files.writeString(original.resolve("corpus.jsonl"),String.join("\n",corpus));
        var dev=new ArrayList<String>();var raw=new ArrayList<Object>();
        for(int i=1;i<=12;i++) {
            String id=String.format("DEV-%03d",i);var expected=i<=8?List.of(sources.get(i-1)):List.<String>of();var forbidden=sources.stream().filter(s->!expected.contains(s)).toList();
            dev.add(SemanticRagPreparationData.JSON.writeValueAsString(Map.of("case_id",id,"query","synthetic "+i,"expected_document_ids",expected,"forbidden_document_ids",forbidden)));
            raw.add(Map.of("caseId",id,"query","synthetic "+i,"expectedDocumentIds",expected,"forbiddenDocumentIds",forbidden,"candidates",List.of(Map.of("rank",1,"score",.9,"documentId",sources.get(0)))));
        }
        Files.writeString(original.resolve("development.jsonl"),String.join("\n",dev));SemanticRagPreparationData.writeNew(ledger.resolve("rag-development-candidates.json"),Map.of("cases",raw));
        var loaded=SemanticMarginQualityMain.developmentInputs(ledger,original,SemanticRagPreparationData.sha256(original.resolve("corpus.jsonl")),SemanticRagPreparationData.sha256(original.resolve("development.jsonl")),SemanticRagPreparationData.sha256(ledger.resolve("rag-development-candidates.json")));
        assertThat(loaded.raw()).hasSize(12);assertThat(loaded.corpus()).hasSize(8);
        assertThat(Files.readString(original.resolve("holdout.jsonl"))).isEqualTo("DO NOT PARSE");
    }
    @Test void infrastructureTypoIsRejectedBeforeGoldOrLease() throws Exception {
        var fields=Map.of("database","fg_sq_1","indexVersion","sq-primary-1","embeddingVersion","e5","embeddingManifestSha256","a".repeat(64),"qdrantEndpoint","http://127.0.0.1:1","qdrantPrefix","prefix","embeddingEndpoint","http://127.0.0.1:2");
        var infrastructure=SemanticRagPreparationData.JSON.valueToTree(fields);
        var selection=SemanticRagPreparationData.JSON.valueToTree(Map.of("identity",fields));
        SemanticMarginQualityData.verifyInfrastructure(selection,infrastructure);
        ((com.fasterxml.jackson.databind.node.ObjectNode)infrastructure).put("indexVersion","typo");
        assertThatThrownBy(()->SemanticMarginQualityData.verifyInfrastructure(selection,infrastructure)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(temp.resolve("holdout-started.json"))).isFalse();
    }
    @Test void freezeOmissionAndWrongAnchorLeaveSealedDataAndLeaseUnconsumed() throws Exception {
        Path sealed=temp.resolve("sealed");Files.createDirectories(sealed);Files.writeString(sealed.resolve("holdout.jsonl"),"INVALID-UNREAD-GOLD");
        Path freeze=temp.resolve("rag-v2-freeze.json");SemanticRagPreparationData.writeNew(freeze,Map.of("schemaVersion",1,"algorithm",SemanticMarginQualityMetrics.ALGORITHM));
        assertThatThrownBy(()->SemanticMarginQualityData.attest(temp,sealed,SemanticRagPreparationData.sha256(freeze))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SemanticMarginQualityData.attest(temp,sealed,"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(temp.resolve("holdout-started.json"))).isFalse();
    }
    @Test void lastCasePersistenceFailureCannotPassAndIsNeverRetried() throws Exception {
        var cases=new ArrayList<SemanticMarginQualityMetrics.Query>();
        for(int i=1;i<=32;i++)cases.add(query("case-"+i,i<=24));
        var reads=new ArrayList<String>();var writes=new ArrayList<String>();
        var execution=SemanticMarginQualityMain.executeHoldout(cases,text->{
            reads.add(text);int number=Integer.parseInt(text.substring("query case-".length()));
            return number<=24?List.of("a"):List.of();
        },outcome->{
            writes.add(outcome.caseId());
            if(outcome.caseId().equals("case-32")) {
                Files.writeString(temp.resolve("case-32.json"),"PARTIAL",StandardOpenOption.CREATE_NEW);
                throw new java.io.IOException("private filesystem detail");
            }
            SemanticRagPreparationData.writeNew(temp.resolve(outcome.caseId()+".json"),outcome);
        });
        assertThat(reads).hasSize(32);assertThat(new HashSet<>(writes)).hasSize(32);assertThat(writes).hasSize(32);
        assertThat(execution.outcomes()).hasSize(32);
        assertThat(execution.outcomes().get(31).state()).isEqualTo("ERROR");
        assertThat(execution.outcomes().get(31).documentIds()).isEmpty();
        assertThat(execution.failureType()).isEqualTo("IOException");
        var metrics=SemanticMarginQualityMetrics.evaluate(cases,execution.outcomes());
        assertThat(metrics.total()).isEqualTo(32);assertThat(metrics.pipelineErrors()).isEqualTo(1);
        assertThat(SemanticMarginQualityMain.qualityPassed(metrics,execution.failureType())).isFalse();
        assertThat(Files.exists(temp.resolve("case-31.json"))).isTrue();
        assertThat(Files.readString(temp.resolve("case-32.json"))).isEqualTo("PARTIAL");
        assertThat(execution.toString()).doesNotContain("private filesystem detail");
    }
    @Test void outerFailureOverridesOtherwisePassingMetrics() {
        var cases=List.of(query("p",true),query("n",false));
        var metrics=SemanticMarginQualityMetrics.evaluate(cases,List.of(
            SemanticMarginQualityMetrics.Outcome.executed("p",List.of("a")),SemanticMarginQualityMetrics.Outcome.executed("n",List.of())));
        assertThat(metrics.passed()).isTrue();
        assertThat(SemanticMarginQualityMain.qualityPassed(metrics,"IOException")).isFalse();
    }
}
