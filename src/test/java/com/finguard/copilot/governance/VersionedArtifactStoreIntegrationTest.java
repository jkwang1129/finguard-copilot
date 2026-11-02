package com.finguard.copilot.governance;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"COPILOT_DB_URL=jdbc:mysql://unused/test","COPILOT_DB_USERNAME=test-only","COPILOT_DB_PASSWORD="})
@Testcontainers
class VersionedArtifactStoreIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.10");
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    VersionedArtifactStore store;
    @BeforeEach void init() {
        jdbc.update("DELETE FROM model_run_manifest");jdbc.update("DELETE FROM model_versioned_artifact");
        jdbc.update("DELETE FROM analysis_input_snapshot");jdbc.update("DELETE FROM analysis_run");
        store=new VersionedArtifactStore(jdbc,tx);
    }
    UUID seed() {
        var id=UUID.randomUUID();var bytes=JdbcAnalysisRunRepository.uuidBytes(id);
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED','reviewer-private',1,0,?,?)",bytes,id.toString(),new byte[32]);
        jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) VALUES(?,1,'{}',?,CURRENT_TIMESTAMP(6),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY))",bytes,new byte[32]);
        return id;
    }
    ObjectNode manifest() {
        return (ObjectNode)CanonicalJson.tree(Map.of("reviewTaskId",1,"reviewTaskVersion",0,"snapshotSha256","0".repeat(64)));
    }
    VersionedArtifactStore.Artifact artifact(String version,int value) {
        return new VersionedArtifactStore.Artifact("price",version,CanonicalJson.tree(Map.of("rate",value)));
    }
    @Test void idempotentlyFreezesAndRefusesChangedVersionOrManifestWithoutPartialWrites() {
        UUID id=seed();store.freeze(id,manifest(),List.of(artifact("v1",2)));
        store.freeze(id,manifest(),List.of(artifact("v1",2)));
        var changed=manifest();changed.put("outputCap",1);
        assertThatThrownBy(()->store.freeze(id,changed,List.of(artifact("v2",8)))).hasMessageContaining("already frozen");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_versioned_artifact",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->store.freeze(seed(),manifest(),List.of(artifact("v1",3)))).hasMessageContaining("content conflict");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_run_manifest",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT manifest_json FROM model_run_manifest",String.class)).doesNotContain("reviewer-private").contains("reviewerKey");
    }
    @Test void concurrentConflictingRegistrationsAcceptOnlyOneContent() throws Exception {
        UUID one=seed(),two=seed();var barrier=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->attempt(barrier,one,2));var second=pool.submit(()->attempt(barrier,two,3));
            barrier.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_run_manifest",Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    boolean attempt(CountDownLatch latch,UUID id,int rate) throws Exception {
        latch.await();try { store.freeze(id,manifest(),List.of(artifact("shared",rate)));return true; }
        catch(com.finguard.copilot.common.error.CopilotException conflict) {return false;}
    }
}
