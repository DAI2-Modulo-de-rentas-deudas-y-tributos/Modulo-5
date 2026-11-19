package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BillLifecycleServiceTests {
    @Test
    void concurrentMultiDebtBillIsNotCancelledWhenOnlyRequestedDebtWasLocked() {
        BillRepository bills = mock(BillRepository.class);
        BillDebtRepository billDebts = mock(BillDebtRepository.class);
        DebtRepository debts = mock(DebtRepository.class);
        InstallmentRepository installments = mock(InstallmentRepository.class);
        PaymentPlanDebtRepository planDebts = mock(PaymentPlanDebtRepository.class);
        AuditService audit = mock(AuditService.class);
        BillLifecycleService service = new BillLifecycleService(bills, billDebts, debts, installments, planDebts, audit);

        Debt requestedDebt = collectibleDebt(1L, "100.00");
        Bill concurrentBill = new Bill();
        concurrentBill.id = 10L;
        concurrentBill.status = BillStatus.ISSUED;
        BillDebt firstLink = billLink(10L, 1L, "100.00");
        BillDebt siblingLink = billLink(10L, 2L, "50.00");

        when(billDebts.findBillIdsByDebtIdIn(List.of(1L))).thenReturn(List.of(), List.of(10L));
        when(debts.findByIdForUpdate(1L)).thenReturn(Optional.of(requestedDebt));
        when(bills.findByIdForUpdate(10L)).thenReturn(Optional.of(concurrentBill));
        when(billDebts.findByBillId(10L)).thenReturn(List.of(firstLink, siblingLink));

        assertThat(service.lockForIssue(List.of(1L))).containsExactly(requestedDebt);

        assertThat(concurrentBill.status).isEqualTo(BillStatus.ISSUED);
        assertThat(concurrentBill.cancellationReason).isNull();
        verify(debts, never()).findByIdForUpdate(2L);
        verifyNoInteractions(audit);
    }

    private Debt collectibleDebt(Long id, String balance) {
        Debt debt = new Debt();
        debt.id = id;
        debt.status = DebtStatus.PENDING;
        debt.currentAmount = new BigDecimal(balance);
        debt.outstandingBalance = new BigDecimal(balance);
        return debt;
    }

    private BillDebt billLink(Long billId, Long debtId, String amount) {
        BillDebt link = new BillDebt();
        link.billId = billId;
        link.debtId = debtId;
        link.amountAtIssue = new BigDecimal(amount);
        return link;
    }
}
