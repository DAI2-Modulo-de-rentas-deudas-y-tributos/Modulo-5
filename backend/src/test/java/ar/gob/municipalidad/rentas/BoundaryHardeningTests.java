package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BoundaryHardeningTests {
    @Test void responseMappersPreserveIdentifiersAmountsAndPublicStatuses() {
        TaxpayerReference taxpayer=new TaxpayerReference();taxpayer.id=1L;taxpayer.taxpayerType=TaxpayerType.CITIZEN;taxpayer.externalId="EXT";taxpayer.externalStatus=TaxpayerStatus.ACTIVE;
        assertThat(ApiResponses.of(taxpayer).externalId()).isEqualTo("EXT");

        CreditBalance credit=new CreditBalance();credit.id=2L;credit.taxpayerId=1L;credit.originalAmount=credit.availableAmount=new BigDecimal("10.00");credit.status=CreditBalanceStatus.AVAILABLE;
        assertThat(ApiResponses.of(credit).availableAmount()).isEqualByComparingTo("10.00");
        CreditBalanceApplication application=new CreditBalanceApplication();application.id=3L;application.creditBalanceId=2L;application.debtId=4L;application.amount=new BigDecimal("2.50");application.status="ACTIVE";
        assertThat(ApiResponses.of(application).amount()).isEqualByComparingTo("2.50");

        PlanExpirationRequest expiration=new PlanExpirationRequest();expiration.id=5L;expiration.paymentPlanId=6L;expiration.status=PlanExpirationStatus.PENDING_APPROVAL;
        assertThat(ApiResponses.of(expiration).status()).isEqualTo(PlanExpirationStatus.PENDING_APPROVAL);
        RefinancingRequest refinancing=new RefinancingRequest();refinancing.id=7L;refinancing.originalPlanId=6L;refinancing.status=RefinancingRequestStatus.PENDING;
        assertThat(ApiResponses.of(refinancing).originalPlanId()).isEqualTo(6L);

        ExemptionRequest request=new ExemptionRequest();request.id=8L;request.taxpayerId=1L;request.status=ExemptionRequestStatus.PENDING;
        assertThat(ApiResponses.of(request).status()).isEqualTo(ExemptionRequestStatus.PENDING);
        Exemption exemption=new Exemption();exemption.id=9L;exemption.taxpayerId=1L;exemption.percentage=new BigDecimal("50");exemption.validFrom=LocalDate.now();exemption.validUntil=LocalDate.now().minusDays(1);exemption.status="ACTIVE";
        assertThat(ApiResponses.of(exemption).expired()).isTrue();

        TicketCase ticket=new TicketCase();ticket.id=10L;ticket.externalTicketId="T-1";ticket.status=TicketCaseStatus.OPEN;
        assertThat(ApiResponses.of(ticket).externalTicketId()).isEqualTo("T-1");
        SocialBenefitReference benefit=new SocialBenefitReference();benefit.id=11L;benefit.externalBenefitId="B-1";benefit.externalStatus="APPROVED";benefit.calculatedStatus=SocialBenefitStatus.ACTIVE;
        assertThat(ApiResponses.of(benefit).status()).isEqualTo(SocialBenefitStatus.ACTIVE);

        ExternalObligation obligation=new ExternalObligation();obligation.id=12L;obligation.externalReferenceId="O-1";obligation.status=ExternalObligationStatus.PROCESSED;
        assertThat(ApiResponses.of(obligation).externalReferenceId()).isEqualTo("O-1");
        IntegrationEventLog event=new IntegrationEventLog();event.id=13L;event.eventId=UUID.randomUUID();event.status=IntegrationEventStatus.PROCESSED;
        assertThat(ApiResponses.of(event).eventId()).isEqualTo(event.eventId.toString());
        ElectronicPaymentAttempt electronic=new ElectronicPaymentAttempt();electronic.id=14L;electronic.amount=new BigDecimal("7.25");electronic.status=ElectronicPaymentStatus.PREVIEWED;
        assertThat(ApiResponses.of(electronic).amount()).isEqualByComparingTo("7.25");
    }

    @Test void infrastructureErrorsAreTranslatedWithoutLeakingTechnicalDetails() {
        GlobalExceptionHandler handler=new GlobalExceptionHandler();MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/v1/payments");
        var integrity=handler.integrity(new DataIntegrityViolationException("secret SQL constraint"),request);
        assertThat(integrity.getStatusCode().value()).isEqualTo(409);
        assertThat(integrity.getBody().message()).doesNotContain("SQL","secret");
        var concurrency=handler.concurrency(new ConcurrencyFailureException("lock internals"),request);
        assertThat(concurrency.getStatusCode().value()).isEqualTo(409);
        var unexpected=handler.unexpected(new IllegalStateException("database password"),request);
        assertThat(unexpected.getStatusCode().value()).isEqualTo(500);
        assertThat(unexpected.getBody().message()).doesNotContain("password");
        assertThat(unexpected.getBody().timestamp()).isBeforeOrEqualTo(OffsetDateTime.now());
    }
}
