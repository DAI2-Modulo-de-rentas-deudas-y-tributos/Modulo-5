package ar.gob.municipalidad.rentas;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CitizenV35FlywayUpgradeTest {
    @ParameterizedTest
    @ValueSource(strings = {"16.13", "17.11"})
    void migratesPopulatedV34WithoutChangingTaxpayerIdentity(String version) {
        try (var postgres = new PostgreSQLContainer<>("postgres:" + version + "-alpine")) {
            postgres.start();
            assertThat(migration(postgres, "34").migrate().targetSchemaVersion).isEqualTo("34");
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            assertThat(jdbc.queryForObject("SHOW server_version", String.class)).startsWith(version);
            UUID citizen = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO taxpayer_reference(taxpayer_type, external_id, display_name, external_status,
                        canonical_citizen_id, created_at, updated_at)
                    VALUES ('CITIZEN', 'synthetic-v34', 'Synthetic', 'ACTIVE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, citizen);
            var before = jdbc.queryForMap("SELECT * FROM taxpayer_reference WHERE external_id='synthetic-v34'");
            var v35 = migration(postgres, "35");
            assertThat(v35.migrate().migrationsExecuted).isOne();
            v35.validate();
            assertThat(jdbc.queryForMap("SELECT * FROM taxpayer_reference WHERE external_id='synthetic-v34'")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM citizen_session", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM citizen_login_window", Long.class)).isZero();
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO citizen_session(token_hash, taxpayer_id, citizen_id, expires_at)
                    VALUES ('synthetic-hash', -1, ?, CURRENT_TIMESTAMP)
                    """, citizen)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    private static Flyway migration(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration", "classpath:db/migration-postgresql", "classpath:db/migration-demo")
                .target(target).load();
    }
}
