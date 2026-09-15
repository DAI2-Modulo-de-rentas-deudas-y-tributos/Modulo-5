package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("prod")
@SpringBootTest
class ProductionSchemaIsolationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @DynamicPropertySource
    static void productionDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:production-schema;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration-production");
    }

    @Test
    void productionRemovesDemoSchemaAndKeepsBusinessSchema() {
        Integer demoTables = jdbc.queryForObject(
            "select count(*) from information_schema.tables where lower(table_name) like 'demo\\_%' escape '\\'",
            Integer.class);
        assertThat(demoTables).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tax_concept", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version='18' and success", Integer.class)).isOne();
        assertThat(context.getBeansOfType(DemoAuthService.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoAuthController.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoUserRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoAuthSessionRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoBootstrapLockRepository.class)).isEmpty();
    }

    @Test
    void productionUpgradeFromV17PreservesHistoryAndRemovesExistingDemoData() {
        String url = "jdbc:h2:mem:production-upgrade;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway common = Flyway.configure().dataSource(url, "sa", "")
            .locations("classpath:db/migration").load();
        common.migrate();
        common.validate();

        JdbcTemplate upgrade = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        upgrade.update("insert into demo_user(username,password_hash,display_name,role,active,created_at,updated_at) values (?,?,?,?,?,?,?)",
            "production.demo", "$2a$10$placeholder", "Debe eliminarse", "SUPERVISOR", true,
            java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now());
        assertThat(upgrade.queryForObject("select count(*) from demo_user", Integer.class)).isOne();

        Flyway production = Flyway.configure().dataSource(url, "sa", "")
            .locations("classpath:db/migration", "classpath:db/migration-production").load();
        production.migrate();
        production.validate();

        assertThat(upgrade.queryForObject(
            "select count(*) from information_schema.tables where lower(table_name) like 'demo\\_%' escape '\\'",
            Integer.class)).isZero();
        assertThat(upgrade.queryForObject("select count(*) from tax_concept", Integer.class)).isPositive();
    }
}
