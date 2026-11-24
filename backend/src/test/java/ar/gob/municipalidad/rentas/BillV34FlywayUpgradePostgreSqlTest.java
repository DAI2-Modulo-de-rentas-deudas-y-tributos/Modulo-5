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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class BillV34FlywayUpgradePostgreSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void v34PreservesAnIssuedMultiDebtBillAndItsPaymentBeforeCancellationAndReissue() {
        Flyway v33 = flyway().target(MigrationVersion.fromVersion("33")).load();
        v33.migrate();
        assertThat(v33.info().current().getVersion().getVersion()).isEqualTo("33");

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        long taxpayerId = insertTaxpayer(jdbc);
        long conceptId = insertConcept(jdbc);
        long obligation1 = insertObligation(jdbc, taxpayerId, conceptId, "v34-legacy-debt-1");
        long obligation2 = insertObligation(jdbc, taxpayerId, conceptId, "v34-legacy-debt-2");
        long debt1 = insertDebt(jdbc, taxpayerId, conceptId, obligation1, "100.00", "80.00");
        long debt2 = insertDebt(jdbc, taxpayerId, conceptId, obligation2, "100.00", "100.00");
        long oldBill = insertBill(jdbc, taxpayerId, "V34-LEGACY-ISSUED", "200.00", "ISSUED");
        link(jdbc, oldBill, debt1, "100.00");
        link(jdbc, oldBill, debt2, "100.00");
        long payment = insertPartialPayment(jdbc, taxpayerId, oldBill, debt1);

        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name='bill' and column_name='cancellation_reason'", Integer.class)).isZero();

        Flyway v34 = flyway().target(MigrationVersion.fromVersion("34")).load();
        v34.migrate();
        v34.validate();
        assertThat(v34.info().current().getVersion().getVersion()).isEqualTo("34");

        assertThat(jdbc.queryForObject("select status from bill where id=?", String.class, oldBill)).isEqualTo("ISSUED");
        assertThat(jdbc.queryForObject("select total_amount from bill where id=?", BigDecimal.class, oldBill)).isEqualByComparingTo("200.00");
        assertThat(jdbc.queryForObject("select cancellation_reason from bill where id=?", String.class, oldBill)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from bill_debt where bill_id=?", Integer.class, oldBill)).isEqualTo(2);
        assertBillDebt(jdbc, oldBill, debt1, "100.00");
        assertBillDebt(jdbc, oldBill, debt2, "100.00");
        assertThat(jdbc.queryForObject("select count(*) from payment where id=? and bill_id=?", Integer.class, payment, oldBill)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_allocation where payment_id=? and debt_id=?", Integer.class, payment, debt1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select external_id from taxpayer_reference where id=?", String.class, taxpayerId))
            .isEqualTo("v34-existing-citizen");
        assertThat(jdbc.queryForObject("select display_name from taxpayer_reference where id=?", String.class, taxpayerId))
            .isEqualTo("V34 Existing Citizen");
        assertDebt(jdbc, debt1, "100.00", "100.00", "80.00", "PARTIALLY_PAID");
        assertDebt(jdbc, debt2, "100.00", "100.00", "100.00", "PENDING");
        assertThat(jdbc.queryForMap("select amount, allocated_amount, unallocated_amount, status, allocation_status, bill_id from payment where id=?", payment))
            .containsEntry("amount", new BigDecimal("20.00"))
            .containsEntry("allocated_amount", new BigDecimal("20.00"))
            .containsEntry("unallocated_amount", new BigDecimal("0.00"))
            .containsEntry("status", "REGISTERED")
            .containsEntry("allocation_status", "FULLY_ALLOCATED")
            .containsEntry("bill_id", oldBill);
        assertThat(jdbc.queryForMap("select amount, status, principal_applied, interest_applied from payment_allocation where payment_id=? and debt_id=?", payment, debt1))
            .containsEntry("amount", new BigDecimal("20.00"))
            .containsEntry("status", "APPLIED")
            .containsEntry("principal_applied", new BigDecimal("20.00"))
            .containsEntry("interest_applied", new BigDecimal("0.00"));

        // This migration fixture verifies persistence and reissue data after V34; the application-level
        // cancellation/reissue path is exercised by BlockedBillPostgreSqlIntegrationTest's
        // partialSeparatePaymentCancelsOldBarcodeAndReissueUsesRemainingDebtBalance test.
        jdbc.update("update bill set status='CANCELLED', cancellation_reason=? where id=?", "DEBT_BALANCE_CHANGED", oldBill);
        long replacement = insertBill(jdbc, taxpayerId, "V34-REISSUED", "180.00", "ISSUED");
        link(jdbc, replacement, debt1, "80.00");
        link(jdbc, replacement, debt2, "100.00");

        assertThat(jdbc.queryForObject("select status from bill where id=?", String.class, oldBill)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select cancellation_reason from bill where id=?", String.class, oldBill)).isEqualTo("DEBT_BALANCE_CHANGED");
        assertThat(jdbc.queryForObject("select total_amount from bill where id=?", BigDecimal.class, replacement)).isEqualByComparingTo("180.00");
        assertThat(jdbc.queryForObject("select count(*) from bill_debt where bill_id=?", Integer.class, replacement)).isEqualTo(2);
        assertBillDebt(jdbc, replacement, debt1, "80.00");
        assertBillDebt(jdbc, replacement, debt2, "100.00");
        assertDebt(jdbc, debt1, "100.00", "100.00", "80.00", "PARTIALLY_PAID");
        assertDebt(jdbc, debt2, "100.00", "100.00", "100.00", "PENDING");
    }

    private static void assertDebt(JdbcTemplate jdbc, long debtId, String original, String current,
                                   String outstanding, String status) {
        assertThat(jdbc.queryForMap("select original_amount, current_amount, outstanding_balance, status from debt where id=?", debtId))
            .containsEntry("original_amount", new BigDecimal(original))
            .containsEntry("current_amount", new BigDecimal(current))
            .containsEntry("outstanding_balance", new BigDecimal(outstanding))
            .containsEntry("status", status);
    }

    private static void assertBillDebt(JdbcTemplate jdbc, long billId, long debtId, String amountAtIssue) {
        assertThat(jdbc.queryForObject("select amount_at_issue from bill_debt where bill_id=? and debt_id=?",
            BigDecimal.class, billId, debtId)).isEqualByComparingTo(amountAtIssue);
    }

    private static FluentConfiguration flyway() {
        return Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration", "classpath:db/migration-postgresql", "classpath:db/migration-demo");
    }

    private static long insertTaxpayer(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
            insert into taxpayer_reference(taxpayer_type,external_id,display_name,external_status,created_at,updated_at)
            values ('CITIZEN','v34-existing-citizen','V34 Existing Citizen','ACTIVE',now(),now()) returning id
            """, Long.class);
    }

    private static long insertConcept(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
            insert into tax_concept(code,name,type,origin_module,active,created_at,updated_at)
            values ('V34_UPGRADE_TEST','V34 Upgrade Test','FEE','M5',true,now(),now()) returning id
            """, Long.class);
    }

    private static long insertObligation(JdbcTemplate jdbc, long taxpayerId, long conceptId, String reference) {
        return jdbc.queryForObject("""
            insert into external_obligation(source_module,external_type,external_reference_id,source_event_id,
                external_taxpayer_type,external_taxpayer_id,taxpayer_id,tax_concept_id,amount,due_date,status,
                retry_count,received_at)
            values ('test','V34_UPGRADE',?,?,'CITIZEN','v34-existing-citizen',?,?,100.00,current_date,'PROCESSED',0,now()) returning id
            """, Long.class, reference, UUID.randomUUID(), taxpayerId, conceptId);
    }

    private static long insertDebt(JdbcTemplate jdbc, long taxpayerId, long conceptId, long obligationId, String amount, String outstanding) {
        return jdbc.queryForObject("""
            insert into debt(taxpayer_id,tax_concept_id,origin_type,external_obligation_id,original_amount,
                current_amount,outstanding_balance,due_date,status,created_at,updated_at)
            values (?,?, 'EXTERNAL_OBLIGATION',?,?,?, ?,current_date,?,now(),now()) returning id
            """, Long.class, taxpayerId, conceptId, obligationId, new BigDecimal(amount),
            new BigDecimal(amount), new BigDecimal(outstanding),
            new BigDecimal(outstanding).compareTo(new BigDecimal(amount)) < 0 ? "PARTIALLY_PAID" : "PENDING");
    }

    private static long insertBill(JdbcTemplate jdbc, long taxpayerId, String number, String amount, String status) {
        return jdbc.queryForObject("""
            insert into bill(number,taxpayer_id,total_amount,issue_date,due_date,status,created_by,created_at)
            values (?,?,?,current_date,current_date + 30,?,'migration-test',now()) returning id
            """, Long.class, number, taxpayerId, new BigDecimal(amount), status);
    }

    private static void link(JdbcTemplate jdbc, long billId, long debtId, String amount) {
        jdbc.update("insert into bill_debt(bill_id,debt_id,amount_at_issue) values (?,?,?)",
            billId, debtId, new BigDecimal(amount));
    }

    private static long insertPartialPayment(JdbcTemplate jdbc, long taxpayerId, long billId, long debtId) {
        long paymentId = jdbc.queryForObject("""
            insert into payment(taxpayer_id,payment_method,amount,allocated_amount,unallocated_amount,status,
                allocation_status,origin,receipt_number,registered_by,paid_at,created_at,bill_id)
            values (?,'CASH',20.00,20.00,0.00,'REGISTERED','FULLY_ALLOCATED','CASH','V34-RECEIPT',
                'migration-test',now(),now(),?) returning id
            """, Long.class, taxpayerId, billId);
        jdbc.update("""
            insert into payment_allocation(payment_id,debt_id,amount,status,allocated_by,allocated_at,target_type,
                principal_applied,interest_applied)
            values (?, ?,20.00,'APPLIED','migration-test',now(),'DEBT',20.00,0.00)
            """, paymentId, debtId);
        return paymentId;
    }
}
