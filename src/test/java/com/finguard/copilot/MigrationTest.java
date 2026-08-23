package com.finguard.copilot;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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
class MigrationTest {

    private static final List<String> REQUIRED_COLUMNS = List.of(
            "id",
            "status",
            "reviewer_username",
            "review_task_id",
            "review_task_version",
            "idempotency_key",
            "request_hash",
            "created_at",
            "updated_at"
    );

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10");

    @Autowired
    private DataSource dataSource;

    @Test
    void flywayCreatesAnalysisRunTableWithRequiredColumns() throws SQLException {
        int tableCount;
        List<String> columns = new ArrayList<>();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet tables = statement.executeQuery("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'analysis_run'
                    """)) {
                tables.next();
                tableCount = tables.getInt(1);
            }

            try (ResultSet resultSet = statement.executeQuery("""
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'analysis_run'
                    ORDER BY ordinal_position
                    """)) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
            }
        }

        assertThat(tableCount).isEqualTo(1);
        assertThat(columns).containsExactlyElementsOf(REQUIRED_COLUMNS);
    }
}
