package com.finguard.copilot.agent.qualify;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemanticRagPreparationDataTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    @Test void changedBytesAreRejectedBeforeAnyCasesAreLoaded() throws Exception {
        Path data = pack("{\"case_id\":\"H-1\"}\n");
        Files.writeString(data.resolve("corpus.jsonl"), "{\"id\":\"changed\"}\n");
        assertThatThrownBy(() -> SemanticRagPreparationData.open(data))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hash");
    }

    @Test void holdoutCannotBeParsedUntilFreezeHasBeenCreatedAndIsLoadedOnlyOnce() throws Exception {
        var data = SemanticRagPreparationData.open(pack("{\"case_id\":\"H-1\"}\n"));
        assertThatThrownBy(data::readHoldout).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("freeze");
        Path freeze = root.resolve("threshold-freeze.json");
        data.freeze(freeze, Map.of("selectedThreshold", .75, "algorithm", "development-only"));
        assertThat(json.readTree(Files.readString(freeze)).path("fileSha256").path("holdout.jsonl").asText())
            .hasSize(64);
        assertThat(data.readHoldout()).hasSize(1);
        assertThatThrownBy(data::readHoldout).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("once");
    }

    @Test void existingFreezeIsNeverOverwrittenAndDoesNotUnlockHoldout() throws Exception {
        var data = SemanticRagPreparationData.open(pack("{}\n"));
        Path freeze = root.resolve("threshold-freeze.json");
        Files.writeString(freeze, "original");
        assertThatThrownBy(() -> data.freeze(freeze, Map.of("selectedThreshold", .8)))
            .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(freeze)).isEqualTo("original");
        assertThatThrownBy(data::readHoldout).isInstanceOf(IllegalStateException.class);
    }

    @Test void changingFrozenConfigurationOrHoldoutAfterFreezeIsRejected() throws Exception {
        Path directory = pack("{}\n");
        var data = SemanticRagPreparationData.open(directory);
        Path freeze = root.resolve("threshold-freeze.json");
        data.freeze(freeze, Map.of("selectedThreshold", .8));
        Files.writeString(directory.resolve("holdout.jsonl"), "{\"changed\":true}\n");
        assertThatThrownBy(data::readHoldout).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hash");
        var other = SemanticRagPreparationData.open(packIn(root.resolve("other"), "{}\n"));
        Path otherFreeze = root.resolve("other-freeze.json");
        other.freeze(otherFreeze, Map.of("selectedThreshold", .8));
        Files.writeString(otherFreeze, "{}");
        assertThatThrownBy(other::readHoldout).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("changed");
    }

    @Test void manifestPathsCannotEscapeTheDataDirectory() throws Exception {
        Path data = pack("{}\n");
        Files.writeString(data.resolve("manifest.json"), json.writeValueAsString(Map.of("files",
            Map.of("../outside", Map.of("sha256", "a".repeat(64), "bytes", 1)))));
        assertThatThrownBy(() -> SemanticRagPreparationData.open(data))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Path pack(String holdout) throws Exception { return packIn(root.resolve("data"), holdout); }
    private Path packIn(Path data, String holdout) throws Exception {
        Files.createDirectories(data);
        var hashes = new LinkedHashMap<String, Object>();
        for (var entry : Map.of("corpus.jsonl", "{\"id\":\"doc\"}\n", "development.jsonl", "{}\n",
                "holdout.jsonl", holdout).entrySet()) {
            Files.writeString(data.resolve(entry.getKey()), entry.getValue());
            hashes.put(entry.getKey(), Map.of("bytes", Files.size(data.resolve(entry.getKey())),
                "sha256", SemanticRagPreparationData.sha256(data.resolve(entry.getKey()))));
        }
        Files.writeString(data.resolve("manifest.json"), json.writeValueAsString(Map.of("files", hashes)));
        return data;
    }
}
