package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test void utcCalendarDateCanBeTomorrowWhileBuenosAiresStillUsesToday(){
        Instant boundary=Instant.parse("2026-11-02T02:30:00Z");
        FiscalProcessingDatePolicy policy=new FiscalProcessingDatePolicy(Clock.fixed(boundary,BUENOS_AIRES));
        LocalDate fiscalDate=policy.today();
        LocalDate utcDate=LocalDate.now(Clock.fixed(boundary,ZoneId.of("UTC")));

        assertThat(utcDate).isEqualTo(LocalDate.of(2026,11,2));
        assertThat(fiscalDate).isEqualTo(LocalDate.of(2026,11,1));
        policy.requireNotFuture(fiscalDate);
        assertThatThrownBy(()->policy.requireNotFuture(utcDate))
            .isInstanceOfSatisfying(BusinessException.class,ex->{assertThat(ex.code).isEqualTo("FUTURE_DATE_NOT_ALLOWED");assertThat(ex.status).isEqualTo(422);});
    }

    @Test void futureRequestsReturn422BeforeCallingEconomicServices() throws Exception {
        LateChargeService lateCharges=mock(LateChargeService.class);
        DueDateService dueDates=mock(DueDateService.class);
        ReconciliationService reconciliations=mock(ReconciliationService.class);
        IdempotencyExecutor idempotency=new IdempotencyExecutor(){@Override public <T> T execute(String operation,String key,Object request,Class<T> responseType,java.util.function.Supplier<T> action){return action.get();}};
        FiscalProcessingController controller=new FiscalProcessingController(lateCharges,dueDates,reconciliations,dates,idempotency);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler(null)).build();
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
        DueDateProcessor processor=mock(DueDateProcessor.class);
        DueDateService dueDates=new DueDateService(runs,processor,dates,mock(CurrentIdentity.class));
        assertThatThrownBy(()->dueDates.process(dates.today().plusDays(1)))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("FUTURE_DATE_NOT_ALLOWED"));
        verifyNoInteractions(runs,processor);
    }

    @Test void dueDateProcessingReturnsAcceptedJobAndKeepsTheRunQueryable() throws Exception {
        LateChargeService lateCharges=mock(LateChargeService.class);DueDateService dueDates=mock(DueDateService.class);
        ReconciliationService reconciliations=mock(ReconciliationService.class);
        IdempotencyExecutor idempotency=new IdempotencyExecutor(){@Override public <T> T execute(String operation,String key,Object request,Class<T> responseType,java.util.function.Supplier<T> action){return action.get();}};
        FiscalProcessingController controller=new FiscalProcessingController(lateCharges,dueDates,reconciliations,dates,idempotency);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler(null)).build();
        var queued=new FiscalProcessingController.DueDateResponse(77L,null,0,0,0,0,0,0,0,DueDateRunStatus.PENDING,dates.today(),OffsetDateTime.now(),null,null,null);
        when(dueDates.enqueue(dates.today())).thenReturn(queued);when(dueDates.get(77L)).thenReturn(queued);

        mvc.perform(post("/api/v1/administration/process-due-dates").contentType("application/json").content("{}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(77)).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/v1/administration/process-due-dates"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(77));
        mvc.perform(get("/api/v1/administration/process-due-dates/77"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(77));
        verify(dueDates,times(2)).enqueue(dates.today());verify(dueDates,times(3)).get(77L);verifyNoInteractions(lateCharges,reconciliations);
    }
}
