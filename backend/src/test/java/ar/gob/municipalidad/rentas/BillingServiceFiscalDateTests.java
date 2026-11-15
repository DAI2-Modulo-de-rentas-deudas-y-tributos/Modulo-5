package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingServiceFiscalDateTests {
    private static final ZoneId BUENOS_AIRES=ZoneId.of("America/Argentina/Buenos_Aires");

    @Test void pricingUsesFiscalDateWhenUtcHasAlreadyAdvancedAndDoesNotQuoteAppliedChargesAgain() {
        LocalDate fiscalDate=LocalDate.of(2026,11,1);
        LocalDate utcDate=LocalDate.of(2026,11,2);
        FiscalProcessingDatePolicy dates=new FiscalProcessingDatePolicy(Clock.fixed(
            Instant.parse("2026-11-02T00:30:00Z"),BUENOS_AIRES));
        assertThat(dates.today()).isEqualTo(fiscalDate);

        Bill bill=new Bill();bill.id=1L;bill.number="BILL-FISCAL-DATE";bill.taxpayerId=2L;
        bill.totalAmount=new BigDecimal("100.00");bill.issueDate=fiscalDate.minusDays(10);
        bill.dueDate=fiscalDate;bill.status=BillStatus.ISSUED;bill.createdBy="test";
        bill.createdAt=OffsetDateTime.parse("2026-10-20T12:00:00-03:00");
        BillDebt link=new BillDebt();link.billId=bill.id;link.debtId=3L;link.amountAtIssue=bill.totalAmount;
        Debt debt=new Debt();debt.id=link.debtId;debt.taxpayerId=bill.taxpayerId;debt.taxConceptId=4L;
        debt.originType=DebtOriginType.EXTERNAL_OBLIGATION;debt.originalAmount=new BigDecimal("100.00");
        debt.currentAmount=new BigDecimal("115.00");debt.outstandingBalance=new BigDecimal("115.00");
        debt.dueDate=fiscalDate.minusDays(5);debt.status=DebtStatus.PENDING;

        BillRepository bills=mock(BillRepository.class);BillDebtRepository billDebts=mock(BillDebtRepository.class);
        DebtRepository debts=mock(DebtRepository.class);TaxpayerRepository taxpayers=mock(TaxpayerRepository.class);
        CurrentIdentity identity=mock(CurrentIdentity.class);LiquidationRepository liquidations=mock(LiquidationRepository.class);
        AdjustmentRepository adjustments=mock(AdjustmentRepository.class);LateChargeRuleRepository rules=mock(LateChargeRuleRepository.class);
        LateChargeApplicationRepository applications=mock(LateChargeApplicationRepository.class);
        when(bills.findById(bill.id)).thenReturn(Optional.of(bill));
        when(billDebts.findByBillIdOrderByIdAsc(bill.id)).thenReturn(List.of(link));
        when(debts.findAllById(List.of(debt.id))).thenReturn(List.of(debt));
        when(liquidations.findAllById(List.of())).thenReturn(List.of());
        when(adjustments.findByDebtIdInAndStatusAndResolvedAtAfter(List.of(debt.id),AdjustmentStatus.APPROVED,bill.createdAt))
            .thenReturn(List.of());

        LateChargeRule rule=new LateChargeRule();rule.id=5L;rule.code="DAILY";
        rule.surchargeRate=new BigDecimal("10.0000");rule.dailyInterestRate=new BigDecimal("1.0000");
        when(rules.findApplicable(any(LocalDate.class),any(Pageable.class))).thenReturn(List.of(rule));
        LateChargeApplication applied=new LateChargeApplication();applied.debtId=debt.id;applied.ruleId=rule.id;
        applied.calculationDate=fiscalDate;applied.totalAdjustment=new BigDecimal("15.00");
        when(applications.findByDebtIdIn(List.of(debt.id))).thenReturn(List.of(applied));
        LateChargeService lateCharges=new LateChargeService(debts,rules,applications,adjustments,
            mock(AuditService.class),identity,dates);
        BillingService billing=new BillingService(bills,billDebts,debts,taxpayers,identity,liquidations,
            adjustments,lateCharges,dates);

        try(MockedStatic<LocalDate> systemDate=mockStatic(LocalDate.class,CALLS_REAL_METHODS)) {
            systemDate.when(LocalDate::now).thenReturn(utcDate);
            ApiDtos.BillDebtPricingResponse price=billing.pricingDetail(bill.id).pricingDetails().get(0);

            assertThat(price.pendingSurchargeAmount()).isZero();
            assertThat(price.pendingInterestAmount()).isZero();
            assertThat(price.updatedPayableAmount()).isEqualByComparingTo("115.00");
        }

        verify(rules).findApplicable(eq(fiscalDate),any(Pageable.class));
        verify(applications).findByDebtIdIn(List.of(debt.id));
    }
}
