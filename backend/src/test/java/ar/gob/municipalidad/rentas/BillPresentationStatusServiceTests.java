package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BillPresentationStatusServiceTests {
    @Test
    void zeroSnapshotWithoutConfirmedAllocationIsNotPresentedAsPaid() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentAllocationRepository allocations = mock(PaymentAllocationRepository.class);
        BillPresentationStatusService service = new BillPresentationStatusService(payments, allocations);

        Bill bill = new Bill();
        bill.id = 7L;
        bill.status = BillStatus.CANCELLED;
        bill.cancellationReason = BillLifecycleService.DEBT_BALANCE_CHANGED;
        BillDebt link = new BillDebt();
        link.billId = bill.id;
        link.debtId = 12L;
        link.amountAtIssue = BigDecimal.ZERO;

        Payment payment = new Payment();
        payment.id = 31L;
        payment.billId = bill.id;
        payment.status = PaymentStatus.CONFIRMED;
        when(payments.findByBillIdIn(List.of(bill.id))).thenReturn(List.of(payment));
        when(allocations.findByPaymentIdIn(List.of(payment.id))).thenReturn(List.of());

        assertThat(service.forBill(bill, List.of(link))).isNull();
    }
}
