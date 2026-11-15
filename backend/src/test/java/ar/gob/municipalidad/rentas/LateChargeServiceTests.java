package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LateChargeServiceTests {
    private static final ZoneId ZONE=ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-20T15:00:00Z"),ZONE);
    private static final LocalDate DATE=LocalDate.of(2026,10,20);

    @Mock DebtRepository debts;
    @Mock LateChargeRuleRepository rules;
    @Mock LateChargeApplicationRepository applications;
    @Mock AdjustmentRepository adjustments;
    @Mock AuditService audit;
    @Mock CurrentIdentity identity;

    private LateChargeService service(){return new LateChargeService(debts,rules,applications,adjustments,audit,identity,new FiscalProcessingDatePolicy(CLOCK));}

    @Test void previewCalculatesSurchargeInterestAndEarlierAdjustmentsWithoutChangingDebt(){
        Debt debt=debt();LateChargeRule rule=rule();
        LateChargeApplication previous=new LateChargeApplication();previous.debtId=debt.id;previous.ruleId=5L;previous.calculationDate=DATE.minusDays(1);previous.totalAdjustment=new BigDecimal("5.00");
        when(debts.findById(debt.id)).thenReturn(Optional.of(debt));
        when(rules.findApplicable(eq(DATE),any())).thenReturn(List.of(rule));
        when(applications.findByDebtIdAndRuleIdAndCalculationDate(debt.id,rule.id,DATE)).thenReturn(Optional.empty());
        when(applications.findByDebtId(debt.id)).thenReturn(List.of(previous));

        FiscalProcessingController.LateChargeResponse response=service().preview(debt.id,DATE);

        assertThat(response.daysOverdue()).isEqualTo(10);
        assertThat(response.surchargeAmount()).isEqualByComparingTo("10.00");
        assertThat(response.interestAmount()).isEqualByComparingTo("10.00");
        assertThat(response.previousAdjustments()).isEqualByComparingTo("5.00");
        assertThat(response.updatedTotal()).isEqualByComparingTo("120.00");
        assertThat(response.applied()).isFalse();
        assertThat(debt.currentAmount).isEqualByComparingTo("100.00");
        verifyNoInteractions(adjustments,audit,identity);
    }

    @Test void previewRoundsHalfCentSurchargeAndInterestUpWithoutMutatingDebt(){
        Debt debt=debt();debt.currentAmount=new BigDecimal("0.05");debt.outstandingBalance=new BigDecimal("0.05");
        LateChargeRule rule=rule();rule.surchargeRate=new BigDecimal("10.0000");rule.dailyInterestRate=new BigDecimal("10.0000");
        debt.dueDate=DATE.minusDays(1);
        when(debts.findById(debt.id)).thenReturn(Optional.of(debt));
        when(rules.findApplicable(eq(DATE),any())).thenReturn(List.of(rule));
        when(applications.findByDebtIdAndRuleIdAndCalculationDate(debt.id,rule.id,DATE)).thenReturn(Optional.empty());
        when(applications.findByDebtId(debt.id)).thenReturn(List.of());

        FiscalProcessingController.LateChargeResponse response=service().preview(debt.id,DATE);

        assertThat(response.daysOverdue()).isEqualTo(1);
        assertThat(response.surchargeAmount()).isEqualByComparingTo("0.01");
        assertThat(response.interestAmount()).isEqualByComparingTo("0.01");
        assertThat(response.totalAdjustment()).isEqualByComparingTo("0.02");
        assertThat(response.updatedTotal()).isEqualByComparingTo("0.07");
        assertThat(debt.currentAmount).isEqualByComparingTo("0.05");
        assertThat(debt.outstandingBalance).isEqualByComparingTo("0.05");
        verifyNoInteractions(adjustments,audit,identity);
    }

    @Test void applyPersistsApplicationAndOnlyNonzeroAdjustmentsThenUpdatesDebt(){
        Debt debt=debt();LateChargeRule rule=rule();
        when(debts.findByIdForUpdate(debt.id)).thenReturn(Optional.of(debt));
        when(rules.findApplicable(eq(DATE),any())).thenReturn(List.of(rule));
        when(applications.findByDebtIdAndRuleIdAndCalculationDate(debt.id,rule.id,DATE)).thenReturn(Optional.empty());
        when(applications.findByDebtId(debt.id)).thenReturn(List.of());
        when(applications.save(any(LateChargeApplication.class))).thenAnswer(call->{LateChargeApplication value=call.getArgument(0);value.id=88L;return value;});
        when(identity.get()).thenReturn(new AuthenticatedIdentity("rentas.demo",null,null));

        FiscalProcessingController.LateChargeResponse response=service().apply(debt.id,DATE);

        assertThat(response.applied()).isTrue();
        assertThat(debt.currentAmount).isEqualByComparingTo("120.00");
        assertThat(debt.outstandingBalance).isEqualByComparingTo("120.00");
        verify(adjustments,times(2)).save(argThat(value->value.status==AdjustmentStatus.APPROVED
            &&value.requestedBy.equals("rentas.demo")&&value.resolutionReason.equals("Procesamiento automático de vencimiento")));
        verify(audit).record(eq("LateChargeApplication"),eq(88L),eq("LATE_CHARGE_APPLIED"),any(LateChargeApplication.class));
    }

    @Test void rejectsPaidDebtWithoutPersistingAnApplicationOrAdjustments(){
        Debt debt=debt();debt.status=DebtStatus.PAID;
        when(debts.findById(debt.id)).thenReturn(Optional.of(debt));
        when(rules.findApplicable(eq(DATE),any())).thenReturn(List.of(rule()));
        when(applications.findByDebtIdAndRuleIdAndCalculationDate(debt.id,5L,DATE)).thenReturn(Optional.empty());
        when(applications.findByDebtId(debt.id)).thenReturn(List.of());

        assertThatThrownBy(()->service().preview(debt.id,DATE))
            .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code).isEqualTo("DEBT_NOT_CHARGEABLE"));

        verify(applications,never()).save(any());
        verifyNoInteractions(adjustments,audit,identity);
    }

    @Test void rejectsDebtOnItsDueDateBecauseItIsNotOverdueYet(){
        Debt debt=debt();debt.dueDate=DATE;
        when(debts.findById(debt.id)).thenReturn(Optional.of(debt));
        when(rules.findApplicable(eq(DATE),any())).thenReturn(List.of(rule()));
        when(applications.findByDebtIdAndRuleIdAndCalculationDate(debt.id,5L,DATE)).thenReturn(Optional.empty());
        when(applications.findByDebtId(debt.id)).thenReturn(List.of());

        assertThatThrownBy(()->service().preview(debt.id,DATE))
            .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code).isEqualTo("DEBT_NOT_OVERDUE"));

        verify(applications,never()).save(any());
        verifyNoInteractions(adjustments,audit,identity);
    }

    private Debt debt(){
        Debt debt=new Debt();debt.id=42L;debt.status=DebtStatus.PENDING;debt.dueDate=DATE.minusDays(10);
        debt.currentAmount=new BigDecimal("100.00");debt.outstandingBalance=new BigDecimal("100.00");return debt;
    }
    private LateChargeRule rule(){
        LateChargeRule rule=new LateChargeRule();rule.id=5L;rule.code="TEST";rule.surchargeRate=new BigDecimal("10.0000");rule.dailyInterestRate=new BigDecimal("1.0000");return rule;
    }
}
