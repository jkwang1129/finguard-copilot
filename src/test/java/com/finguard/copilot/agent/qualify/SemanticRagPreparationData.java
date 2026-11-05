package com.finguard.copilot.agent.qualify;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

/** Local evaluation data boundary. Hashing holdout bytes never parses or exposes its labels. */
final class SemanticRagPreparationData {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path directory;
    private final JsonNode manifest;
    private final String manifestSha;
    private final Map<String, String> hashes;
    private Path freezeFile;
    private String freezeSha;
    private boolean holdoutLoaded;

    private SemanticRagPreparationData(Path directory, JsonNode manifest, String manifestSha,
            Map<String, String> hashes) {
        this.directory = directory;
        this.manifest = manifest;
        this.manifestSha = manifestSha;
        this.hashes = Map.copyOf(hashes);
    }

    static SemanticRagPreparationData open(Path directory) throws IOException {
        Path base = directory.toAbsolutePath().normalize();
        byte[] manifestBytes = Files.readAllBytes(base.resolve("manifest.json"));
        JsonNode manifest = JSON.readTree(manifestBytes);
        if (!manifest.path("files").isObject()) throw new IllegalArgumentException("Missing data hash manifest");
        var hashes = new LinkedHashMap<String, String>();
        var fields = manifest.path("files").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String name = field.getKey();
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
                throw new IllegalArgumentException("Invalid data manifest path");
            JsonNode spec = field.getValue();
            String expected = spec.path("sha256").asText();
            if (!expected.matches("[a-f0-9]{64}") || !spec.path("bytes").isIntegralNumber())
                throw new IllegalArgumentException("Invalid data hash specification");
            Path file = base.resolve(name);
            if (Files.isSymbolicLink(file) || !sha256(file).equals(expected)
                    || Files.size(file) != spec.path("bytes").asLong())
                throw new IllegalArgumentException("Frozen data hash mismatch: " + name);
            hashes.put(name, expected);
        }
        if (!hashes.keySet().containsAll(Set.of("corpus.jsonl", "development.jsonl", "holdout.jsonl")))
            throw new IllegalArgumentException("Frozen data hash entries incomplete");
        return new SemanticRagPreparationData(base, manifest, sha256(manifestBytes), hashes);
    }

    JsonNode manifest() { return manifest.deepCopy(); }
    String manifestSha256() { return manifestSha; }
    Map<String, String> fileSha256() { return hashes; }
    List<JsonNode> readCorpus() throws IOException { return readVerified("corpus.jsonl"); }
    List<JsonNode> readDevelopment() throws IOException { return readVerified("development.jsonl"); }

    void freeze(Path destination, Map<String, Object> configuration) throws IOException {
        if (freezeFile != null) throw new IllegalStateException("Threshold already frozen");
        Object selected = configuration.get("selectedThreshold");
        if (!(selected instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < -1 || number.doubleValue() > 1)
            throw new IllegalArgumentException("Invalid freeze threshold");
        var value = new LinkedHashMap<>(configuration);
        value.put("dataManifestSha256", manifestSha);
        value.put("fileSha256", hashes);
        writeNew(destination, value);
        freezeSha = sha256(destination);
        freezeFile = destination;
    }

    List<JsonNode> readHoldout() throws IOException {
        if (freezeFile == null) throw new IllegalStateException("Persist threshold freeze before loading holdout");
        if (holdoutLoaded) throw new IllegalStateException("Holdout may be loaded only once");
        if (!sha256(freezeFile).equals(freezeSha)) throw new IllegalStateException("Persisted freeze changed");
        // Consume the single load even if input parsing subsequently fails. There is no implicit retry.
        holdoutLoaded = true;
        return readVerified("holdout.jsonl");
    }

    private List<JsonNode> readVerified(String name) throws IOException {
        if (!sha256(directory.resolve("manifest.json")).equals(manifestSha)
                || !sha256(directory.resolve(name)).equals(hashes.get(name)))
            throw new IllegalArgumentException("Frozen data hash mismatch: " + name);
        var rows = new ArrayList<JsonNode>();
        for (String line : Files.readString(directory.resolve(name)).lines().toList()) {
            if (line.isBlank()) throw new IllegalArgumentException("Blank frozen data row");
            JsonNode row = JSON.readTree(line);
            if (!row.isObject()) throw new IllegalArgumentException("Frozen data row must be an object");
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    static void writeNew(Path destination, Object value) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        try (var channel = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
