package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class PercentageBenefitRegressionTests {
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired SocialBenefitIntegrationService benefitIntegration;
    @Autowired LiquidationComponentRepository components;
    @Autowired DebtRepository debts;

    @BeforeEach
    void authenticateAsRentas() {
        var roles = List.of(new SimpleGrantedAuthority("ROLE_RENTAS"));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("agent-1", null), null, roles));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void percentageBaseAndActiveSocialBenefitAreAppliedOnceAndPersisted() {
        Fixture fixture = fixture(YearMonth.now(), "50.00", YearMonth.now().atEndOfMonth());
        ApiDtos.LiquidationRequest request = request(fixture, new BigDecimal("100000.00"));

        ApiDtos.LiquidationPreview preview = liquidations.preview(request);

        assertThat(preview.baseAmount()).isEqualByComparingTo("2000.00");
        assertThat(preview.discountAmount()).isEqualByComparingTo("1000.00");
        assertThat(preview.exemptionAmount()).isEqualByComparingTo("0.00");
        assertThat(preview.finalAmount()).isEqualByComparingTo("1000.00");
        assertThat(preview.components()).filteredOn(x -> x.type() == LiquidationComponentType.SOCIAL_BENEFIT)
            .singleElement().extracting(ApiDtos.LiquidationComponentResponse::amount)
            .isEqualTo(new BigDecimal("1000.00"));

        Liquidation persisted = liquidations.create(request);
        assertThat(persisted.baseAmount).isEqualByComparingTo("2000.00");
        assertThat(persisted.discountAmount).isEqualByComparingTo("1000.00");
        assertThat(persisted.finalAmount).isEqualByComparingTo("1000.00");
        assertThat(components.findByLiquidationIdOrderById(persisted.id))
            .filteredOn(x -> x.type == LiquidationComponentType.SOCIAL_BENEFIT)
            .singleElement().extracting(x -> x.amount).isEqualTo(new BigDecimal("1000.00"));
        assertThat(debts.findByLiquidationId(persisted.id).orElseThrow().originalAmount)
            .isEqualByComparingTo("1000.00");
    }

    @Test
    void percentageBaseWithoutBenefitHasNoDiscount() {
        Fixture fixture = fixtureWithoutBenefit(YearMonth.now());

        ApiDtos.LiquidationPreview preview = liquidations.preview(request(fixture, new BigDecimal("100000.00")));

        assertThat(preview.baseAmount()).isEqualByComparingTo("2000.00");
        assertThat(preview.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(preview.finalAmount()).isEqualByComparingTo("2000.00");
    }

    @Test
    void expiredBenefitDoesNotReduceNextFiscalPeriod() {
        YearMonth nextPeriod = YearMonth.now().plusMonths(1);
        Fixture fixture = fixture(nextPeriod, "50.00", YearMonth.now().minusMonths(1).atDay(1),
            YearMonth.now().atEndOfMonth());

        ApiDtos.LiquidationPreview preview = liquidations.preview(request(fixture, new BigDecimal("100000.00")));

        assertThat(preview.baseAmount()).isEqualByComparingTo("2000.00");
        assertThat(preview.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(preview.finalAmount()).isEqualByComparingTo("2000.00");
    }

    @Test
    void percentageAndBenefitAmountsUseTwoDecimalHalfUpRounding() {
        Fixture fixture = fixture(YearMonth.now(), "50.00", YearMonth.now().atEndOfMonth());

        ApiDtos.LiquidationPreview preview = liquidations.preview(request(fixture, new BigDecimal("100000.25")));

        assertThat(preview.baseAmount()).isEqualByComparingTo("2000.01");
        assertThat(preview.discountAmount()).isEqualByComparingTo("1000.01");
        assertThat(preview.finalAmount()).isEqualByComparingTo("1000.00");
        assertThat(preview.baseAmount().scale()).isEqualTo(2);
        assertThat(preview.discountAmount().scale()).isEqualTo(2);
        assertThat(preview.finalAmount().scale()).isEqualTo(2);
    }

    private Fixture fixture(YearMonth period, String discount, LocalDate validUntil) {
        return fixture(period, discount, period.atDay(1), validUntil);
    }

    private Fixture fixture(YearMonth period, String discount, LocalDate validFrom, LocalDate validUntil) {
        Fixture fixture = fixtureWithoutBenefit(period);
        ApiDtos.EventEnvelope<ApiDtos.SocialBenefitEventData> event = new ApiDtos.EventEnvelope<>(
            UUID.randomUUID(), "socialBenefitUpdated", OffsetDateTime.now(), "M8",
            new ApiDtos.SocialBenefitEventData("BEN-" + UUID.randomUUID(), fixture.taxpayer.externalId,
                "SOCIAL", SocialBenefitStatus.ACTIVE, new BigDecimal(discount), validFrom, validUntil,
                List.of(fixture.concept.code)));
        benefitIntegration.consume(event);
        return fixture;
    }

    private Fixture fixtureWithoutBenefit(YearMonth period) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        TaxpayerReference taxpayer = catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.CITIZEN, "SCRUM670-" + suffix, "12345678", null, "Contribuyente SCRUM-670"));
        TaxConcept concept = catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
            "SCRUM670_" + suffix, "Concepto SCRUM-670", null, TaxConceptType.FEE, "M5"));
        TaxConfiguration configuration = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            concept.id, CalculationType.PERCENTAGE, new BigDecimal("2.0000"), null, null, null,
            true, true, LocalDate.now().minusDays(1), null));
        catalog.submit(configuration.id);
        catalog.approve(configuration.id);
        return new Fixture(taxpayer, concept, period, LocalDate.now().plusDays(10));
    }

    private ApiDtos.LiquidationRequest request(Fixture fixture, BigDecimal taxableBase) {
        return new ApiDtos.LiquidationRequest(fixture.taxpayer.id, fixture.concept.id,
            fixture.period.toString(), taxableBase, fixture.dueDate);
    }

    private record Fixture(TaxpayerReference taxpayer, TaxConcept concept, YearMonth period, LocalDate dueDate) {}
}
