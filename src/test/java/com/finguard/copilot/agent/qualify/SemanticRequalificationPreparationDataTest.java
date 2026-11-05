package com.finguard.copilot.agent.qualify;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SemanticRequalificationPreparationDataTest {
    @TempDir Path directory;
    private Path preparation() {
        return Path.of("src/test/resources/semantic-requalification/original-preparation.json");
    }
    private Path corpus() { return Path.of("tools/agent-evaluation/data/semantic-quality-v1/corpus.jsonl"); }
    @Test void opensOnlyFrozenCorpusWithoutDevelopmentOrHoldoutFiles() throws Exception {
        Path isolatedCorpus = directory.resolve("corpus.jsonl");
        Files.copy(corpus(), isolatedCorpus);
        var input = assertDoesNotThrow(() -> SemanticRequalificationPreparationData.open(preparation(), isolatedCorpus));
        assertEquals(8, input.corpus().size());
        assertEquals(0.8569634500000001, input.original().path("minimumScore").doubleValue());
        assertFalse(Files.exists(directory.resolve("holdout.jsonl")));
    }
    @Test void refusesTamperedParentBeforeParsingOrServices() throws Exception {
        Path tampered = directory.resolve("preparation.json");
        Files.writeString(tampered, "{unparseable parent}");
        var failure = assertThrows(IllegalArgumentException.class, () -> SemanticRequalificationPreparationData.open(tampered, corpus()));
        assertEquals("Frozen original preparation changed", failure.getMessage());
    }
    @Test void refusesDriftedCorpusBeforeParsingOrServices() throws Exception {
        Path tampered = directory.resolve("corpus.jsonl");
        Files.copy(corpus(), tampered);
        Files.writeString(tampered, " ", StandardOpenOption.APPEND);
        var failure = assertThrows(IllegalArgumentException.class, () -> SemanticRequalificationPreparationData.open(preparation(), tampered));
        assertEquals("Frozen corpus changed", failure.getMessage());
    }
    @Test void outputLeaseNeverReplacesEvidence() throws Exception {
        Path output = directory.resolve("lease.json");
        SemanticRagPreparationData.writeNew(output, java.util.Map.of("original",true));
        byte[] before = Files.readAllBytes(output);
        assertThrows(FileAlreadyExistsException.class, () -> SemanticRagPreparationData.writeNew(output, java.util.Map.of("original",false)));
        assertArrayEquals(before, Files.readAllBytes(output));
    }
}
