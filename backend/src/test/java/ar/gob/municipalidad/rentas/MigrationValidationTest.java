package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("migrationtest") @SpringBootTest
class MigrationValidationTest {
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username",()->"sa");
        registry.add("spring.datasource.password",()->"");
    }
    @Test void flywaySchemaMatchesJpaModel() {
        assertThat(jdbc.queryForObject("select max(cast(version as integer)) from flyway_schema_history where success",Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version is not null",Integer.class)).isEqualTo(12);
    }
}
