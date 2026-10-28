package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FiscalProcessingDateValidationTests {
    private static final ZoneId BUENOS_AIRES=ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-27T02:00:00Z"),BUENOS_AIRES);
    private final FiscalProcessingDatePolicy dates=new FiscalProcessingDatePolicy(CLOCK);

    @Test void usesBuenosAiresDateAndRejectsOnlyLaterDates(){
        assertThat(dates.today()).isEqualTo(LocalDate.of(2026,10,26));
        dates.requireNotFuture(dates.today());
        dates.requireNotFuture(dates.today().minusDays(1));
        assertThatThrownBy(()->dates.requireNotFuture(dates.today().plusDays(1)))
            .isInstanceOfSatisfying(BusinessException.class,ex->{assertThat(ex.code).isEqualTo("FUTURE_DATE_NOT_ALLOWED");assertThat(ex.status).isEqualTo(422);});
    }

    @Test void futureRequestsReturn422BeforeCallingEconomicServices() throws Exception {
        LateChargeService lateCharges=mock(LateChargeService.class);
        DueDateService dueDates=mock(DueDateService.class);
        ReconciliationService reconciliations=mock(ReconciliationService.class);
        FiscalProcessingController controller=new FiscalProcessingController(lateCharges,dueDates,reconciliations,dates);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        String tomorrow=dates.today().plusDays(1).toString();

        mvc.perform(post("/api/v1/debts/123/late-charge-preview").contentType("application/json").content("{\"calculationDate\":\""+tomorrow+"\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("FUTURE_DATE_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/debts/123/late-charges").contentType("application/json").content("{\"calculationDate\":\""+tomorrow+"\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("FUTURE_DATE_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/administration/process-due-dates").contentType("application/json").content("{\"processingDate\":\""+tomorrow+"\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("FUTURE_DATE_NOT_ALLOWED"));

        verifyNoInteractions(lateCharges,dueDates,reconciliations);
    }

    @Test void servicesRejectFutureDatesBeforeReadingOrMutatingPersistence() {
        DebtRepository debts=mock(DebtRepository.class);
        LateChargeRuleRepository rules=mock(LateChargeRuleRepository.class);
        LateChargeApplicationRepository applications=mock(LateChargeApplicationRepository.class);
        AdjustmentRepository adjustments=mock(AdjustmentRepository.class);
        AuditService audit=mock(AuditService.class);
        CurrentIdentity lateChargeIdentity=mock(CurrentIdentity.class);
        LateChargeService lateCharges=new LateChargeService(debts,rules,applications,adjustments,audit,lateChargeIdentity,dates);
        assertThatThrownBy(()->lateCharges.preview(123L,dates.today().plusDays(1)))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("FUTURE_DATE_NOT_ALLOWED"));
        assertThatThrownBy(()->lateCharges.apply(123L,dates.today().plusDays(1)))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("FUTURE_DATE_NOT_ALLOWED"));
        verifyNoInteractions(debts,rules,applications,adjustments,audit,lateChargeIdentity);

        DueDateProcessingRepository runs=mock(DueDateProcessingRepository.class);
        DebtRepository dueDebts=mock(DebtRepository.class);
        InstallmentRepository installments=mock(InstallmentRepository.class);
        PaymentPlanRepository plans=mock(PaymentPlanRepository.class);
        PaymentPlanConfigurationRepository configurations=mock(PaymentPlanConfigurationRepository.class);
        PlanExpirationRepository expirations=mock(PlanExpirationRepository.class);
        ConfirmedIntegrationOutbox outbox=mock(ConfirmedIntegrationOutbox.class);
        CurrentIdentity identity=mock(CurrentIdentity.class);
        AuditService dueDateAudit=mock(AuditService.class);
        DueDateService dueDates=new DueDateService(runs,dueDebts,installments,plans,configurations,expirations,
            mock(LateChargeService.class),outbox,identity,dueDateAudit,dates);
        assertThatThrownBy(()->dueDates.process(dates.today().plusDays(1)))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("FUTURE_DATE_NOT_ALLOWED"));
        verifyNoInteractions(runs,dueDebts,installments,plans,configurations,expirations,outbox,identity,dueDateAudit);
    }
}
