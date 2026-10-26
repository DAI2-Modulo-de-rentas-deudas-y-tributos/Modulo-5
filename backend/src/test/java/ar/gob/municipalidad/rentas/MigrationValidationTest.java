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
        registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/migration-test-h2,classpath:db/migration-m8-test-h2");
    }
    @Test void flywaySchemaMatchesJpaModel() {
        assertThat(jdbc.queryForObject("select max(cast(version as integer)) from flyway_schema_history where success",Integer.class)).isEqualTo(28);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version is not null",Integer.class)).isEqualTo(27);
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='bill'",String.class))
            .contains("idempotency_key","idempotency_fingerprint");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='ticket_case'",String.class))
            .contains("public_id","correlation_id","last_source_event_id","last_external_updated_at","request_type","subcategory");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='demo_user'",String.class))
            .contains("integration_actor_id");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='debt'",String.class))
            .contains("integration_id");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='payment'",String.class))
            .contains("integration_id");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name in ('payment_plan_request','payment_plan')",String.class))
            .contains("integration_id");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='taxpayer_reference'",String.class))
            .contains("canonical_citizen_id");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='social_benefit_reference'",String.class))
            .contains("external_reason","resolution_status","source_payload_hash","received_at");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='integration_event_log'",String.class))
            .contains("business_key");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='payment_reversal_request'",String.class))
            .contains("resolution_reason");
        assertThat(jdbc.queryForList("select code from tax_concept where code in ('TASA_SERVICIOS','ABL','PATENTE') order by code",String.class))
            .containsExactly("ABL","PATENTE","TASA_SERVICIOS");
    }
}
