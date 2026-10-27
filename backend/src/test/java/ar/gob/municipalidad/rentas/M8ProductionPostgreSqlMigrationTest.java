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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("prod")
@Testcontainers(disabledWithoutDocker=true)
class M8ProductionPostgreSqlMigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource static void isolatedProductionDatabase(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test void m8MigrationsKeepProductionSchemaWithoutDemoUsers(){
        assertThat(jdbc.queryForObject("select current_setting('server_version')",String.class)).startsWith("17.");
        assertThat(jdbc.queryForObject("select to_regclass('public.demo_user')::text",String.class)).isNull();
        assertThat(jdbc.queryForObject("select to_regclass('public.demo_login_attempt')::text",String.class)).isNull();
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success and version in ('18','24','25','26','27','28') order by installed_rank",String.class))
            .containsExactly("18","24","25","26","27","28");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version='29'",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='debt'",String.class))
            .contains("integration_id");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name in ('payment_plan_request','payment_plan')",String.class))
            .contains("integration_id");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='social_benefit_reference'",String.class))
            .contains("external_reason","resolution_status","source_payload_hash","received_at");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name in ('social_benefit_rule','social_benefit_event_history')",Integer.class)).isEqualTo(2);
    }
}
