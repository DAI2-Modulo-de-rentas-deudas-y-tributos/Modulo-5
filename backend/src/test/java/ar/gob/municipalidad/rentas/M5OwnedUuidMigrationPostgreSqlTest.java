package ar.gob.municipalidad.rentas;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class M5OwnedUuidMigrationPostgreSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");

    @Test
    void upgradesExistingV23RowsToV27InCommonAndProductionLocations() {
        assertUpgrade(List.of("classpath:db/migration", "classpath:db/migration-postgresql"), false);
        assertUpgrade(List.of("classpath:db/migration", "classpath:db/migration-production", "classpath:db/migration-postgresql"), true);
    }

    private void assertUpgrade(List<String> locations, boolean production) {
        Flyway before = flyway(locations).target(MigrationVersion.fromVersion("23")).load();
        before.clean();
        before.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        seedV23Rows(jdbc);
        List<Long> debtIds = primaryKeys(jdbc, "debt");
        List<Long> liquidationIds = primaryKeys(jdbc, "liquidation");
        List<Long> requestIds = primaryKeys(jdbc, "exemption_request");
        List<Long> exemptionIds = primaryKeys(jdbc, "exemption");
        List<Long> paymentIds = primaryKeys(jdbc, "payment");

        Flyway after = flyway(locations).load();
        after.migrate();
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success and version in ('24','25','26','27') order by installed_rank", String.class))
            .containsExactly("24", "25", "26", "27");
        assertStableUniqueIds(jdbc, "debt", debtIds);
        assertStableUniqueIds(jdbc, "liquidation", liquidationIds);
        assertStableUniqueIds(jdbc, "exemption_request", requestIds);
        assertStableUniqueIds(jdbc, "exemption", exemptionIds);
        assertStableUniqueIds(jdbc, "payment", paymentIds);
        Map<Long, UUID> generatedDebtIds = uuidRows(jdbc, "debt");
        Map<Long, UUID> generatedLiquidationIds = uuidRows(jdbc, "liquidation");
        Map<Long, UUID> generatedRequestIds = uuidRows(jdbc, "exemption_request");
        Map<Long, UUID> generatedExemptionIds = uuidRows(jdbc, "exemption");
        Map<Long, UUID> generatedPaymentIds = uuidRows(jdbc, "payment");
        assertThat(jdbc.queryForObject("select count(*) from taxpayer_reference where canonical_citizen_id is not null", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from social_benefit_reference where resolution_status='LEGACY' and source_payload_hash='" + "0".repeat(64) + "'", Integer.class)).isEqualTo(1);
        if (production) assertThat(jdbc.queryForObject("select to_regclass('public.demo_user')::text", String.class)).isNull();

        after.migrate();
        assertThat(uuidRows(jdbc, "debt")).containsExactlyEntriesOf(generatedDebtIds);
        assertThat(uuidRows(jdbc, "liquidation")).containsExactlyEntriesOf(generatedLiquidationIds);
        assertThat(uuidRows(jdbc, "exemption_request")).containsExactlyEntriesOf(generatedRequestIds);
        assertThat(uuidRows(jdbc, "exemption")).containsExactlyEntriesOf(generatedExemptionIds);
        assertThat(uuidRows(jdbc, "payment")).containsExactlyEntriesOf(generatedPaymentIds);
    }

    private FluentConfiguration flyway(List<String> locations) {
        return Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(locations.toArray(String[]::new))
            .cleanDisabled(false);
    }

    private void seedV23Rows(JdbcTemplate jdbc) {
        long taxpayer = jdbc.queryForObject("""
            insert into taxpayer_reference(taxpayer_type, external_id, display_name, external_status, created_at, updated_at)
            values ('CITIZEN','MIGRATION-TEST','Migration Test','ACTIVE',now(),now()) returning id
            """, Long.class);
        long concept = jdbc.queryForObject("""
            insert into tax_concept(code,name,type,origin_module,active,created_at,updated_at)
            values ('MIGRATION_TEST','Migration Test','MUNICIPAL','M5',true,now(),now()) returning id
            """, Long.class);
        long configuration = jdbc.queryForObject("""
            insert into tax_configuration(tax_concept_id,version,calculation_type,fixed_amount,partial_payment_allowed,payment_plan_allowed,valid_from,status,created_by,created_at)
            values (?,1,'FIXED',100,true,true,current_date,'APPROVED','migration-test',now()) returning id
            """, Long.class, concept);
        for (int i = 0; i < 2; i++) {
            long liquidation = jdbc.queryForObject("""
                insert into liquidation(taxpayer_id,tax_concept_id,tax_configuration_id,configuration_version,period,taxable_base,base_amount,exemption_amount,final_amount,due_date,status,created_by,issued_at)
                values (?,?,?,1,?,100,100,0,100,current_date,'ISSUED','migration-test',now()) returning id
                """, Long.class, taxpayer, concept, configuration, "2026-0" + (i + 1));
            jdbc.update("""
                insert into debt(taxpayer_id,tax_concept_id,origin_type,liquidation_id,original_amount,current_amount,outstanding_balance,due_date,status,created_at,updated_at)
                values (?,?, 'LIQUIDATION',?,100,100,100,current_date,'PENDING',now(),now())
                """, taxpayer, concept, liquidation);
            long request = jdbc.queryForObject("""
                insert into exemption_request(taxpayer_id,tax_concept_id,reason,requested_percentage,requested_from,status,requested_by,requested_at)
                values (?,?,'migration fixture',50,current_date,'APPROVED','migration-test',now()) returning id
                """, Long.class, taxpayer, concept);
            jdbc.update("""
                insert into exemption(request_id,taxpayer_id,tax_concept_id,percentage,valid_from,status,approved_by,approved_at)
                values (?,?,?,50,current_date,'ACTIVE','migration-test',now())
                """, request, taxpayer, concept);
            jdbc.update("""
                insert into payment(taxpayer_id,payment_method,amount,allocated_amount,unallocated_amount,status,allocation_status,
                    origin,receipt_number,registered_by,paid_at,created_at)
                values (?,'CASH',100,0,100,'CONFIRMED','UNALLOCATED','CASHIER',?,'migration-test',now(),now())
                """, taxpayer, "MIGRATION-REC-" + i);
        }
        jdbc.update("""
            insert into social_benefit_reference(external_benefit_id,taxpayer_id,external_citizen_id,benefit_type,external_status,discount_percentage,valid_from,source_event_id,updated_at,calculated_status,benefits_payload,external_source_event_id)
            values ('MIGRATION-BENEFIT',?,'MIGRATION-TEST','SOCIAL','ACTIVE',50,current_date,?,now(),'ACTIVE','[]',?)
            """, taxpayer, UUID.randomUUID(), UUID.randomUUID().toString());
    }

    private Map<Long, UUID> uuidRows(JdbcTemplate jdbc, String table) {
        return jdbc.query("select id,integration_id from " + table, rs -> {
            Map<Long, UUID> rows = new java.util.HashMap<>();
            while (rs.next()) rows.put(rs.getLong(1), rs.getObject(2, UUID.class));
            return rows;
        });
    }

    private List<Long> primaryKeys(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("select id from " + table, Long.class);
    }

    private void assertStableUniqueIds(JdbcTemplate jdbc, String table, List<Long> expectedPrimaryKeys) {
        Map<Long, UUID> after = uuidRows(jdbc, table);
        assertThat(after.values()).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(primaryKeys(jdbc, table)).containsExactlyInAnyOrderElementsOf(expectedPrimaryKeys);
    }
}
