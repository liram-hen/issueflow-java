// Tests §4.4 Documentation — application.yaml and compose.yml configuration correctness
package com.att.tdp.issueflow;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class InfrastructureConfigurationTests {

    /** §4.4 — application.yaml contains the correct PostgreSQL connection URL, credentials, and Docker Compose lifecycle settings that match the documented run instructions. */
    @Test
    void mainApplicationConfigUsesPostgresComposeDefaults() throws Exception {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yaml"));

        assertThat(applicationYaml)
                .contains("jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:issueflow}")
                .contains("username: ${DB_USERNAME:issueflow}")
                .contains("password: ${DB_PASSWORD:issueflow}")
                .contains("driver-class-name: org.postgresql.Driver")
                .contains("database-platform: org.hibernate.dialect.PostgreSQLDialect")
                .contains("mode: never")
                .contains("file: compose.yml")
                .contains("lifecycle-management: start-only");
    }

    /** §4.4 — compose.yml uses the postgres:17 image, the same env var names as application.yaml, and a working healthcheck — keeping Docker Compose and the datasource config in sync. */
    @Test
    void composeConfigMatchesDatasourceEnvironmentVariables() throws Exception {
        String composeYaml = Files.readString(Path.of("compose.yml"));

        assertThat(composeYaml)
                .contains("image: postgres:17")
                .contains("POSTGRES_USER: ${DB_USERNAME:-issueflow}")
                .contains("POSTGRES_PASSWORD: ${DB_PASSWORD:-issueflow}")
                .contains("POSTGRES_DB: ${DB_NAME:-issueflow}")
                .contains("published: ${DB_PORT:-5432}")
                .contains("pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}");
    }

    /** §4.4 — The test application.yaml uses H2 in-memory and disables Docker Compose so the test suite runs on any machine without Docker. */
    @Test
    void testConfigurationDoesNotRequireDockerOrPostgres() throws Exception {
        String testApplicationYaml = Files.readString(Path.of("src/test/resources/application.yaml"));

        assertThat(testApplicationYaml)
                .contains("jdbc:h2:mem:db;MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE")
                .contains("compose:")
                .contains("enabled: false")
                .contains("ddl-auto: update");
    }
}
