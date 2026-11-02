package com.finguard.copilot.governance;

import java.util.*;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

final class GovernanceDatabaseFixtures {
    static UUID seed(JdbcTemplate jdbc,TransactionTemplate tx,String reviewer,FactSnapshot snapshot) {
        UUID id=UUID.randomUUID();byte[] bytes=JdbcAnalysisRunRepository.uuidBytes(id);
        long task=snapshot==null?1:snapshot.facts().reviewTask().id(),version=snapshot==null?0:snapshot.facts().reviewTask().version();
        byte[] hash=snapshot==null?new byte[32]:snapshot.sha256();
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED',?,?,?,?,?)",
                bytes,reviewer,task,version,id.toString(),new byte[32]);
        jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) VALUES(?,1,?,?,?,?)",bytes,
                snapshot==null?"{}":new String(new SnapshotCodec().canonicalBytes(snapshot),StandardCharsets.UTF_8),hash,
                snapshot==null?Timestamp.valueOf("2026-11-02 06:00:00"):Timestamp.from(snapshot.capturedAt()),
                snapshot==null?Timestamp.valueOf("2026-12-02 06:00:00"):Timestamp.from(snapshot.expiresAt()));
        var price=CanonicalJson.tree(Map.of("provider","deepseek","modelId","deepseek-flash","currency","CNY","inputPerMillion",2,"outputPerMillion",8));
        var limits=CanonicalJson.tree(Map.of("maxCallCny","0.1","maxReviewerDailyCny","0.10","maxProviderDailyCny","0.10","maxGlobalDailyCny","0.10"));
        ObjectNode manifest=(ObjectNode)CanonicalJson.tree(Map.of("reviewTaskId",task,"reviewTaskVersion",version,"snapshotSha256",HexFormat.of().formatHex(hash),
                "price",Map.of("version","price-v1","sha256",CanonicalJson.hash(price)),"budget",Map.of("version","budget-v1","sha256",CanonicalJson.hash(limits)),
                "model",Map.of("version","model-v1"),"prompt",Map.of("version","prompt-v1"),"inputUpperBound",22000,"outputCap",2000));
        manifest.put("reservedEstimateCny","0.06000000");
        new VersionedArtifactStore(jdbc,tx).freeze(id,manifest,List.of(new VersionedArtifactStore.Artifact("price","price-v1",price),
                new VersionedArtifactStore.Artifact("budget","budget-v1",limits)));
        return id;
    }
}
