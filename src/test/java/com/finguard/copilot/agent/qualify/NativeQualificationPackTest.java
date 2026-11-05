package com.finguard.copilot.agent.qualify;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import com.finguard.copilot.governance.CanonicalJson;
import org.junit.jupiter.api.Test;

class NativeQualificationPackTest {
    String read(String version) throws Exception {
        return Files.readString(Path.of("tools/agent-evaluation/data/phase5-native-"+version+".jsonl"));
    }
    @Test void distinctNewPackRetainsOriginalScenarioAndBusinessInputs() throws Exception {
        String contents=read("requalification-v2");
        var rows=NativeProviderQualificationMain.validatedCases(contents,"phase5-native-retest-20261104",CanonicalJson.sha256(contents));
        var original=read("qualification-v1").lines().toList();
        assertThat(rows).hasSize(12);
        for(int i=0;i<12;i++){
            var old=NativeProviderQualificationMain.JSON.readTree(original.get(i));
            var row=rows.get(i);
            assertThat(row.path("sourceCaseId").asText()).isEqualTo(old.path("case_id").asText());
            for(String field:new String[]{"scenario","tools","amount","account"})assertThat(row.get(field)).isEqualTo(old.get(field));
        }
    }
    @Test void wrongHashAndMixedOldIdsAreRejectedBeforeAnyExternalWork() throws Exception {
        String contents=read("requalification-v2");
        assertThatThrownBy(()->NativeProviderQualificationMain.validatedCases(contents,"phase5-native-retest-20261104","0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        String mixed=contents.replace("RQ-006","NQ-006");
        assertThatThrownBy(()->NativeProviderQualificationMain.validatedCases(mixed,"phase5-native-retest-20261104",CanonicalJson.sha256(mixed))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->NativeProviderQualificationMain.validatedCases(contents,"arbitrary-batch",CanonicalJson.sha256(contents))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void historicalPackCannotBePassedAsTheNewBatch() throws Exception {
        String old=read("qualification-v1");
        assertThat(NativeProviderQualificationMain.validatedCases(old,"phase5-native-20261104",CanonicalJson.sha256(old))).hasSize(12);
        assertThatThrownBy(()->NativeProviderQualificationMain.validatedCases(old,"phase5-native-retest-20261104",CanonicalJson.sha256(old))).isInstanceOf(IllegalArgumentException.class);
    }
}
