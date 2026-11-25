package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** Derives a user-facing payment state without changing the bill's technical lifecycle. */
@Service
class BillPresentationStatusService {
    static final String PAID = "PAID";
    static final String PARTIAL_PAYMENT = "PARTIAL_PAYMENT";
    static final String PAYMENT_REVERSED = "PAYMENT_REVERSED";

    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;

    BillPresentationStatusService(PaymentRepository payments, PaymentAllocationRepository allocations) {
        this.payments = payments;
        this.allocations = allocations;
    }

    String forBill(Bill bill, List<BillDebt> links) {
        return forBills(List.of(bill), Map.of(bill.id, links)).get(bill.id);
    }

    Map<Long, String> forBills(Collection<Bill> bills, Map<Long, List<BillDebt>> linksByBill) {
        List<Bill> eligible = bills.stream()
            .filter(bill -> bill.status == BillStatus.CANCELLED
                && BillLifecycleService.DEBT_BALANCE_CHANGED.equals(bill.cancellationReason))
            .filter(bill -> !linksByBill.getOrDefault(bill.id, List.of()).isEmpty())
            .toList();
        if (eligible.isEmpty()) return Map.of();

        List<Long> billIds = eligible.stream().map(bill -> bill.id).toList();
        List<Payment> relatedPayments = payments.findByBillIdIn(billIds);
        if (relatedPayments.isEmpty()) return Map.of();
        Map<Long, Payment> paymentById = relatedPayments.stream()
            .collect(Collectors.toMap(payment -> payment.id, payment -> payment));
        List<PaymentAllocation> relatedAllocations = allocations.findByPaymentIdIn(paymentById.keySet());

        Map<Long, Map<Long, BigDecimal>> confirmedByBillAndDebt = new HashMap<>();
        Set<Long> reversedBills = new HashSet<>();
        Map<Long, Set<Long>> debtIdsByBill = eligible.stream().collect(Collectors.toMap(
            bill -> bill.id,
            bill -> linksByBill.getOrDefault(bill.id, List.of()).stream()
                .map(link -> link.debtId).collect(Collectors.toSet())));

        for (PaymentAllocation allocation : relatedAllocations) {
            Payment payment = paymentById.get(allocation.paymentId);
            if (payment == null || allocation.targetType != AllocationTargetType.DEBT || allocation.debtId == null
                || !debtIdsByBill.getOrDefault(payment.billId, Set.of()).contains(allocation.debtId)) continue;
            if (payment.status == PaymentStatus.CONFIRMED && "ACTIVE".equals(allocation.status)) {
                confirmedByBillAndDebt.computeIfAbsent(payment.billId, ignored -> new HashMap<>())
                    .merge(allocation.debtId, allocation.amount, BigDecimal::add);
            } else if (payment.status == PaymentStatus.REVERSED && "REVERSED".equals(allocation.status)
                && allocation.amount.signum() > 0) {
                reversedBills.add(payment.billId);
            }
        }

        Map<Long, String> result = new HashMap<>();
        for (Bill bill : eligible) {
            List<BillDebt> links = linksByBill.getOrDefault(bill.id, List.of());
            Map<Long, BigDecimal> paidByDebt = confirmedByBillAndDebt.getOrDefault(bill.id, Map.of());
            boolean hasConfirmedAllocation = paidByDebt.values().stream().anyMatch(amount -> amount.signum() > 0);
            boolean fullyPaid = links.stream().allMatch(link -> paidByDebt
                .getOrDefault(link.debtId, BigDecimal.ZERO).compareTo(link.amountAtIssue) >= 0);
            if (hasConfirmedAllocation && fullyPaid) result.put(bill.id, PAID);
            else if (hasConfirmedAllocation) result.put(bill.id, PARTIAL_PAYMENT);
            else if (reversedBills.contains(bill.id)) result.put(bill.id, PAYMENT_REVERSED);
        }
        return Map.copyOf(result);
    }
}
