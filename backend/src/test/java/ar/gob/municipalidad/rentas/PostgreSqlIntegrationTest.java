package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker=true)
class PostgreSqlIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;

    @Test void contextStartsAndFlywayAppliesEveryMigration(){
        assertThat(jdbc.queryForObject("select max(cast(version as integer)) from flyway_schema_history where success",Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success",Integer.class)).isEqualTo(8);
    }

    @Test void economicChecksAndIdempotencyUniquesExist(){
        List<String> checks=jdbc.queryForList("select pg_get_constraintdef(oid) from pg_constraint where contype='c'",String.class);
        assertThat(checks).anyMatch(x->x.contains("origin_type")&&x.contains("liquidation_id")&&x.contains("external_obligation_id"));
        assertThat(checks).anyMatch(x->x.contains("target_type")&&x.contains("debt_id")&&x.contains("installment_id"));
        List<String> uniques=jdbc.queryForList("select pg_get_constraintdef(oid) from pg_constraint where contype in ('p','u')",String.class);
        assertThat(uniques).anyMatch(x->x.contains("event_id"));
        assertThat(uniques).anyMatch(x->x.contains("taxpayer_id")&&x.contains("tax_concept_id")&&x.contains("period"));
        assertThat(uniques).anyMatch(x->x.contains("source_module")&&x.contains("external_type")&&x.contains("external_reference_id"));
    }

    @Test void recommendedOperationalIndexesExist(){
        List<String> indexes=jdbc.queryForList("select indexdef from pg_indexes where schemaname='public'",String.class);
        assertThat(indexes).anyMatch(x->x.contains("debt")&&x.contains("taxpayer_id"));
        assertThat(indexes).anyMatch(x->x.contains("payment")&&x.contains("paid_at"));
        assertThat(indexes).anyMatch(x->x.contains("integration_event_log")&&x.contains("status"));
        assertThat(indexes).anyMatch(x->x.contains("liquidation_component")&&x.contains("liquidation_id"));
    }
}
