package com.finguard.copilot.governance;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** First registration binds a version to content. No update/delete API is exposed. */
public class VersionedArtifactStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public VersionedArtifactStore(JdbcTemplate jdbc,TransactionTemplate transactions) { this.jdbc=jdbc;this.transactions=transactions; }
    public record Artifact(String kind,String version,JsonNode content) {
        public Artifact {
            if(kind==null || !kind.matches("[A-Za-z0-9._:-]{1,32}") || version==null || !version.matches("[A-Za-z0-9._:-]{1,128}") || content==null)
                throw RunManifestService.rejected("invalid artifact version");
            content=content.deepCopy();
        }
        @Override public JsonNode content() { return content.deepCopy(); }
    }
    public void freeze(UUID analysisId,ObjectNode supplied,List<Artifact> artifacts) {
        var stable=supplied.deepCopy();
        var immutable=List.copyOf(artifacts);
        transactions.executeWithoutResult(status->{
            byte[] id=JdbcAnalysisRunRepository.uuidBytes(analysisId);
            var identity=jdbc.queryForMap("""
                    SELECT r.reviewer_username,r.review_task_id,r.review_task_version,s.facts_sha256
                    FROM analysis_run r JOIN analysis_input_snapshot s ON s.analysis_id=r.id
                    WHERE r.id=? AND s.deleted_at IS NULL
                    """,id);
            if(((Number)identity.get("review_task_id")).longValue()!=stable.path("reviewTaskId").asLong() ||
                    ((Number)identity.get("review_task_version")).longValue()!=stable.path("reviewTaskVersion").asLong() ||
                    !HexFormat.of().formatHex((byte[])identity.get("facts_sha256")).equals(stable.path("snapshotSha256").asText()))
                throw RunManifestService.rejected("manifest identity does not match captured run");
            stable.put("reviewerKey",CanonicalJson.sha256((String)identity.get("reviewer_username")));
            // Stable locking order avoids opposite-order artifact registration deadlocks.
            immutable.stream().sorted(Comparator.comparing(Artifact::kind).thenComparing(Artifact::version)).forEach(artifact->{
                String hash=CanonicalJson.hash(artifact.content());
                jdbc.update("INSERT IGNORE INTO model_versioned_artifact(artifact_kind,version_id,content_sha256,content_json) VALUES(?,?,?,?)",
                        artifact.kind(),artifact.version(),hash,CanonicalJson.encode(artifact.content()));
                String existing=jdbc.queryForObject("SELECT content_sha256 FROM model_versioned_artifact WHERE artifact_kind=? AND version_id=? FOR UPDATE",
                        String.class,artifact.kind(),artifact.version());
                if(!hash.equals(existing)) throw RunManifestService.rejected("artifact version content conflict");
            });
            String hash=CanonicalJson.hash(stable);
            jdbc.update("INSERT IGNORE INTO model_run_manifest(analysis_id,manifest_sha256,manifest_json) VALUES(?,?,?)",id,hash,CanonicalJson.encode(stable));
            String existing=jdbc.queryForObject("SELECT manifest_sha256 FROM model_run_manifest WHERE analysis_id=? FOR UPDATE",String.class,id);
            if(!hash.equals(existing)) throw RunManifestService.rejected("analysis manifest is already frozen");
        });
    }
}
