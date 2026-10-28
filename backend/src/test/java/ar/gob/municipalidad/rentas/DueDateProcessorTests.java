package ar.gob.municipalidad.rentas;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DueDateProcessorTests {
    private final DueDateProcessingRepository runs=mock(DueDateProcessingRepository.class);
    private final DebtRepository debts=mock(DebtRepository.class);
    private final InstallmentRepository installments=mock(InstallmentRepository.class);
    private final PaymentPlanRepository plans=mock(PaymentPlanRepository.class);
    private final PaymentPlanConfigurationRepository configurations=mock(PaymentPlanConfigurationRepository.class);
    private final PlanExpirationRepository expirations=mock(PlanExpirationRepository.class);
    private final LateChargeService lateCharges=mock(LateChargeService.class);
    private final ConfirmedIntegrationOutbox outbox=mock(ConfirmedIntegrationOutbox.class);
    private final CurrentIdentity identity=mock(CurrentIdentity.class);
    private final AuditService audit=mock(AuditService.class);
    private final EntityManager entityManager=mock(EntityManager.class);
    private final DueDateProcessor processor=new DueDateProcessor(runs,debts,installments,plans,configurations,
        expirations,lateCharges,outbox,identity,audit,new FiscalProcessingDatePolicy(java.time.Clock.systemUTC()));

    DueDateProcessorTests(){
        ReflectionTestUtils.setField(processor,"entityManager",entityManager);
        when(identity.get()).thenReturn(new AuthenticatedIdentity("rentas.demo",null,null));
    }

    @Test void alreadyProcessedDateReturnsExistingRunWithoutRepeatingEconomicEffects(){
        LocalDate date=LocalDate.of(2026,10,20);
        DueDateProcessing previous=run(date,41L);
        previous.debtsOverdue=3;
        when(runs.findByProcessingDate(date)).thenReturn(Optional.of(previous));

        FiscalProcessingController.DueDateResponse response=processor.process(date);

        assertThat(response.id()).isEqualTo(41L);
        assertThat(response.debtsOverdue()).isEqualTo(3);
        verifyNoInteractions(debts,installments,plans,configurations,expirations,lateCharges,outbox,audit);
    }

    @Test void overdueDebtWithoutChargeRuleIsPublishedAndCountedAsProcessingError(){
        LocalDate date=LocalDate.of(2026,10,20);
        mockNewRun(date);
        when(debts.count()).thenReturn(1L);
        Debt debt=new Debt();debt.id=7L;debt.status=DebtStatus.PENDING;debt.outstandingBalance=new BigDecimal("20.00");debt.dueDate=date.minusDays(1);
        when(debts.findByStatusInAndOutstandingBalanceGreaterThanAndDueDateLessThanAndIdGreaterThanOrderByIdAsc(any(),any(),eq(date),eq(0L),any()))
            .thenReturn(List.of(debt),List.of());
        when(lateCharges.hasApplicableRule(date)).thenReturn(false);

        FiscalProcessingController.DueDateResponse response=processor.process(date);

        assertThat(response.debtsScanned()).isEqualTo(1);
        assertThat(response.debtsOverdue()).isEqualTo(1);
        assertThat(response.errors()).isEqualTo(1);
        verify(outbox).overdueDebt(debt,date);
        verify(lateCharges,never()).apply(any(),any());
        verify(runs).save(any(DueDateProcessing.class));
    }

    @Test void marksOpenInstallmentsOverdueAndRequestsExpirationOnlyPastConfiguredTolerance(){
        LocalDate date=LocalDate.of(2026,10,20);
        mockNewRun(date);
        when(debts.count()).thenReturn(0L);
        when(lateCharges.hasApplicableRule(date)).thenReturn(false);
        Installment pending=new Installment();pending.id=10L;pending.status=InstallmentStatus.PENDING;
        Installment alreadyOverdue=new Installment();alreadyOverdue.id=11L;alreadyOverdue.status=InstallmentStatus.OVERDUE;
        when(installments.findByStatusInAndOutstandingAmountGreaterThanAndDueDateLessThanAndIdGreaterThanOrderByIdAsc(any(),any(),eq(date),eq(0L),any()))
            .thenReturn(List.of(pending,alreadyOverdue),List.of());
        PaymentPlan plan=new PaymentPlan();plan.id=12L;plan.configurationId=13L;
        when(plans.findByStatusAndConfigurationIdIsNotNullAndIdGreaterThanOrderByIdAsc(eq(PaymentPlanStatus.ACTIVE),eq(0L),any()))
            .thenReturn(List.of(plan),List.of());
        PaymentPlanConfiguration configuration=new PaymentPlanConfiguration();configuration.id=13L;configuration.maxOverdueInstallments=1;
        when(configurations.findAllById(List.of(13L))).thenReturn(List.of(configuration));
        PlanInstallmentCount overdueCount=mock(PlanInstallmentCount.class);
        when(overdueCount.getPaymentPlanId()).thenReturn(12L);when(overdueCount.getInstallmentCount()).thenReturn(2L);
        when(installments.countByPlanIdsAndStatus(List.of(12L),InstallmentStatus.OVERDUE)).thenReturn(List.of(overdueCount));
        when(expirations.findPlanIdsByStatus(List.of(12L),PlanExpirationStatus.PENDING_APPROVAL)).thenReturn(List.of());

        FiscalProcessingController.DueDateResponse response=processor.process(date);

        assertThat(response.installmentsOverdue()).isEqualTo(1);
        assertThat(pending.status).isEqualTo(InstallmentStatus.OVERDUE);
        assertThat(alreadyOverdue.status).isEqualTo(InstallmentStatus.OVERDUE);
        assertThat(response.plansDefaulted()).isEqualTo(1);
        verify(expirations).save(argThat(request->request.paymentPlanId.equals(12L)
            &&request.status==PlanExpirationStatus.PENDING_APPROVAL&&request.requestedBy.equals("rentas.demo")));
        verify(audit).record(eq("PlanExpirationRequest"),any(),eq("PLAN_DEFAULT_DETECTED"),any(PlanExpirationRequest.class));
    }

    private void mockNewRun(LocalDate date){
        when(runs.findByProcessingDate(date)).thenReturn(Optional.empty());
        DueDateProcessing[] created=new DueDateProcessing[1];
        when(runs.saveAndFlush(any(DueDateProcessing.class))).thenAnswer(invocation->{
            DueDateProcessing run=invocation.getArgument(0);run.id=99L;created[0]=run;return run;
        });
        when(runs.findById(99L)).thenAnswer(invocation->Optional.ofNullable(created[0]));
    }

    private DueDateProcessing run(LocalDate date,Long id){
        DueDateProcessing run=new DueDateProcessing();run.id=id;run.processingDate=date;run.processedAt=OffsetDateTime.now();run.processedBy="rentas.demo";return run;
    }
}
