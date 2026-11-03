package com.finguard.copilot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "COPILOT_DB_URL=jdbc:mysql://unused/test",
        "COPILOT_DB_USERNAME=test-only",
        "COPILOT_DB_PASSWORD="
})
@Testcontainers
class Phase2MigrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10");

    @Autowired
    private DataSource dataSource;

    @Test
    void createsThePhase2KnowledgeTablesAndRequiredColumns() throws SQLException {
        assertThat(strings("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name LIKE 'knowledge_%'
                ORDER BY table_name
                """)).containsExactly(
                "knowledge_chunk",
                "knowledge_citation",
                "knowledge_document",
                "knowledge_index_membership",
                "knowledge_index_version");

        assertThat(columns("knowledge_document")).containsExactlyEntriesOf(linkedMap(
                "document_id", "varchar(128)|NO",
                "source_version", "varchar(128)|NO",
                "source_type", "varchar(32)|NO",
                "title", "varchar(255)|NO",
                "canonical_checksum", "char(64)|NO",
                "sanitized", "tinyint(1)|NO",
                "status", "varchar(32)|NO",
                "registered_at", "datetime(6)|NO"
        ));
        assertThat(columns("knowledge_chunk")).containsExactlyEntriesOf(linkedMap(
                "chunk_id", "varchar(255)|NO",
                "document_id", "varchar(128)|NO",
                "document_version", "varchar(128)|NO",
                "ordinal", "int|NO",
                "content", "longtext|NO",
                "content_checksum", "char(64)|NO",
                "section_path", "varchar(512)|NO",
                "metadata_json", "json|NO"
        ));
        assertThat(columns("knowledge_index_version")).containsExactlyEntriesOf(linkedMap(
                "index_version", "varchar(128)|NO",
                "status", "varchar(32)|NO",
                "created_at", "datetime(6)|NO",
                "published_at", "datetime(6)|YES",
                "replaced_version", "varchar(128)|YES",
                "evaluation_recall_at_k", "decimal(10,9)|YES",
                "evaluation_citation_precision", "decimal(10,9)|YES",
                "evaluation_no_answer_correctness", "decimal(10,9)|YES",
                "evaluator_version", "varchar(128)|YES",
                "evaluated_at", "datetime(6)|YES"
        ));
    }

    @Test
    void enforcesPhase2IdentityIndexesAndForeignKeys() throws SQLException {
        assertThat(indexes("knowledge_document"))
                .containsEntry("PRIMARY", List.of("document_id", "source_version"))
                .containsEntry("uk_knowledge_document_checksum",
                        List.of("document_id", "source_version", "canonical_checksum"));
        assertThat(indexes("knowledge_chunk"))
                .containsEntry("PRIMARY", List.of("chunk_id"))
                .containsEntry("uk_knowledge_chunk_ordinal",
                        List.of("document_id", "document_version", "ordinal"));
        assertThat(indexes("knowledge_index_version"))
                .containsEntry("PRIMARY", List.of("index_version"));
        assertThat(indexes("knowledge_index_membership"))
                .containsEntry("PRIMARY", List.of("index_version", "chunk_id"));
        assertThat(indexes("knowledge_citation"))
                .containsEntry("PRIMARY", List.of("citation_id"));

        assertThat(constraintNames()).contains(
                "fk_knowledge_chunk_document",
                "fk_knowledge_membership_index",
                "fk_knowledge_membership_chunk",
                "fk_knowledge_citation_run",
                "fk_knowledge_citation_chunk",
                "fk_knowledge_citation_index");

        Map<String, String> checks = checkConstraints();
        assertThat(checks.get("ck_knowledge_document_source_type"))
                .contains("policy", "rule_explanation", "manual", "runbook", "sanitized_case");
        assertThat(checks.get("ck_knowledge_document_sanitized")).contains("sanitized", "0", "1");
        assertThat(checks.get("ck_knowledge_chunk_ordinal")).contains("ordinal", ">= 0");
        assertThat(checks.get("ck_knowledge_index_status"))
                .contains("draft", "evaluating", "published", "retired", "failed");
    }

    @Test
    void v1AndV2MigrationResourcesRemainUnchanged() throws Exception {
        assertThat(resourceSha256("db/migration/V1__create_analysis_run.sql"))
                .isEqualTo("fa27c6e000e355a80eb58943b68c55b17296e7c9e63c8dba6160b3baa9bfc7e5");
        assertThat(resourceSha256("db/migration/V2__create_phase1_fact_loop_tables.sql"))
                .isEqualTo("313c0307df6e74258f91eee4b1ada0c3a7df8f1c09958660fdfce723c8f6f1bc");
        assertThat(strings("""
                SELECT version
                FROM flyway_schema_history
                WHERE success = TRUE
                ORDER BY installed_rank
                """)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    }

    private Map<String, String> columns(String tableName) throws SQLException {
        return pairs("""
                SELECT column_name, CONCAT(column_type, '|', is_nullable)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = '%s'
                ORDER BY ordinal_position
                """.formatted(tableName));
    }

    private Map<String, List<String>> indexes(String tableName) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT index_name, column_name
                     FROM information_schema.statistics
                     WHERE table_schema = DATABASE()
                       AND table_name = '%s'
                     ORDER BY index_name, seq_in_index
                     """.formatted(tableName))) {
            while (rows.next()) {
                result.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>())
                        .add(rows.getString(2));
            }
        }
        return result;
    }

    private List<String> constraintNames() throws SQLException {
        return strings("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name LIKE 'knowledge_%'
                  AND constraint_type = 'FOREIGN KEY'
                ORDER BY constraint_name
                """);
    }

    private Map<String, String> checkConstraints() throws SQLException {
        return pairs("""
                SELECT cc.constraint_name, LOWER(REPLACE(cc.check_clause, '`', ''))
                FROM information_schema.check_constraints cc
                JOIN information_schema.table_constraints tc
                  ON tc.constraint_schema = cc.constraint_schema
                 AND tc.constraint_name = cc.constraint_name
                WHERE cc.constraint_schema = DATABASE()
                  AND tc.table_name LIKE 'knowledge_%'
                ORDER BY cc.constraint_name
                """);
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }

    private Map<String, String> pairs(String sql) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.put(rows.getString(1), rows.getString(2));
            }
        }
        return result;
    }

    private String resourceSha256(String resource) throws IOException, NoSuchAlgorithmException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as("resource %s", resource).isNotNull();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes());
            return java.util.HexFormat.of().formatHex(digest);
        }
    }

    private static Map<String, String> linkedMap(String... entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(entries[index], entries[index + 1]);
        }
        return result;
    }
}
