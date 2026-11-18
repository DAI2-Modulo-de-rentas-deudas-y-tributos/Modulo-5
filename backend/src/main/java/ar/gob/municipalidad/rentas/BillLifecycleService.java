package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
class BillLifecycleService {
    static final String DEBT_BALANCE_CHANGED = "DEBT_BALANCE_CHANGED";
    private final BillRepository bills;
    private final BillDebtRepository billDebts;
    private final DebtRepository debts;
    private final InstallmentRepository installments;
    private final PaymentPlanDebtRepository planDebts;
    private final AuditService audit;

    BillLifecycleService(BillRepository bills, BillDebtRepository billDebts, DebtRepository debts,
        InstallmentRepository installments, PaymentPlanDebtRepository planDebts, AuditService audit) {
        this.bills = bills;
        this.billDebts = billDebts;
        this.debts = debts;
        this.installments = installments;
        this.planDebts = planDebts;
        this.audit = audit;
    }

    @Transactional
    List<Debt> lockForIssue(Collection<Long> debtIds) {
        List<Long> requestedIds = debtIds.stream().distinct().sorted().toList();
        List<Long> initialBillIds = billDebts.findBillIdsByDebtIdIn(requestedIds);
        Map<Long, Debt> lockedDebts = lockDebtsWithBillSiblings(requestedIds, initialBillIds);
        List<Long> relatedBillIds = billDebts.findBillIdsByDebtIdIn(requestedIds);
        List<Bill> lockedBills = lockBills(relatedBillIds);

        for (Bill bill : lockedBills) {
            List<BillDebt> links = billDebts.findByBillId(bill.id);
            boolean allDebtsLocked = !links.isEmpty() && links.stream().allMatch(link -> lockedDebts.containsKey(link.debtId));
            if (bill.status == BillStatus.ISSUED && allDebtsLocked && !matchesCurrentBalances(links, lockedDebts)) {
                cancel(bill);
            }
        }
        return requestedIds.stream().map(id -> lockedDebts.get(id)).toList();
    }

    void requireNoIssuedBill(Collection<Long> debtIds) {
        CatalogService.requireConflict(!billDebts.existsIssuedBillByDebtIdIn(debtIds), "DEBT_ALREADY_BILLED",
            "Una o más deudas ya pertenecen a una boleta emitida");
    }

    @Transactional
    List<Bill> lockForPayment(Payment payment, List<ApiDtos.AllocationRequest> requests) {
        Set<Long> debtIds = new TreeSet<>();
        for (ApiDtos.AllocationRequest request : requests) {
            if (request.debtId() != null) debtIds.add(request.debtId());
            if (request.installmentId() != null) {
                Long planId = installments.findPaymentPlanIdById(request.installmentId())
                    .orElseThrow(() -> CatalogService.notFound("Cuota"));
                planDebtIds(planId).forEach(debtIds::add);
            }
        }
        if (payment.billId != null) {
            List<BillDebt> links = billDebts.findByBillId(payment.billId);
            if (links.isEmpty()) bills.findById(payment.billId).orElseThrow(() -> CatalogService.notFound("Boleta"));
            links.stream().map(link -> link.debtId).forEach(debtIds::add);
        }
        List<Long> initialBillIds = debtIds.isEmpty() ? List.of() : billDebts.findBillIdsByDebtIdIn(debtIds);
        Map<Long, Debt> lockedDebts = lockDebtsWithBillSiblings(debtIds, initialBillIds);
        Set<Long> billIds = new TreeSet<>(debtIds.isEmpty() ? List.of() : billDebts.findBillIdsByDebtIdIn(debtIds));
        if (payment.billId != null) billIds.add(payment.billId);
        List<Bill> lockedBills = lockBills(billIds);

        if (payment.billId != null) {
            Bill bill = lockedBills.stream().filter(item -> item.id.equals(payment.billId)).findFirst()
                .orElseThrow(() -> CatalogService.notFound("Boleta"));
            List<BillDebt> links = billDebts.findByBillId(bill.id);
            boolean allDebtsLocked = !links.isEmpty() && links.stream().allMatch(link -> lockedDebts.containsKey(link.debtId));
            CatalogService.require(bill.status == BillStatus.ISSUED && allDebtsLocked && matchesCurrentBalances(links, lockedDebts),
                "BILL_NOT_PAYABLE", "La boleta no admite pagos");
        }
        return lockedBills;
    }

    List<Long> planDebtIds(Long planId) {
        return planDebts.findByPaymentPlanId(planId).stream()
            .filter(link -> link.status == PaymentPlanDebtStatus.ACTIVE)
            .map(link -> link.debtId).toList();
    }

    @Transactional
    List<Bill> lockForDebtBalanceChange(Collection<Long> debtIds) {
        if (debtIds.isEmpty()) return List.of();
        List<Long> billIds = billDebts.findBillIdsByDebtIdIn(debtIds);
        lockDebtsWithBillSiblings(debtIds, billIds);
        return lockBills(billDebts.findBillIdsByDebtIdIn(debtIds));
    }

    void cancelForDebtBalanceChange(Collection<Long> debtIds, List<Bill> lockedBills) {
        if (debtIds.isEmpty()) return;
        for (Bill bill : lockedBills) {
            if (bill.status == BillStatus.ISSUED && !matchesCurrentBalances(bill)) cancel(bill);
        }
    }

    private Map<Long, Debt> lockDebtsWithBillSiblings(Collection<Long> debtIds, Collection<Long> initialBillIds) {
        Set<Long> lockDebtIds = new TreeSet<>(debtIds);
        if (!initialBillIds.isEmpty()) lockDebtIds.addAll(billDebts.findDebtIdsByBillIdIn(initialBillIds));
        return lockDebts(lockDebtIds);
    }

    private Map<Long, Debt> lockDebts(Collection<Long> debtIds) {
        Map<Long, Debt> result = new LinkedHashMap<>();
        debtIds.stream().distinct().sorted().forEach(id -> result.put(id,
            debts.findByIdForUpdate(id).orElseThrow(() -> CatalogService.notFound("Deuda"))));
        return result;
    }

    private List<Bill> lockBills(Collection<Long> billIds) {
        List<Bill> result = new ArrayList<>();
        billIds.stream().distinct().sorted().forEach(id -> bills.findByIdForUpdate(id).ifPresent(result::add));
        return result;
    }

    private boolean matchesCurrentBalances(List<BillDebt> links, Map<Long, Debt> lockedDebts) {
        return !links.isEmpty() && links.stream().allMatch(link -> {
            Debt debt = lockedDebts.get(link.debtId);
            return debt != null && debt.isCollectible() && link.amountAtIssue.compareTo(debt.outstandingBalance) == 0;
        });
    }

    private boolean matchesCurrentBalances(Bill bill) {
        List<BillDebt> links = billDebts.findByBillId(bill.id);
        return !links.isEmpty() && links.stream().allMatch(link -> debts.findById(link.debtId)
            .filter(debt -> debt.isCollectible() && link.amountAtIssue.compareTo(debt.outstandingBalance) == 0)
            .isPresent());
    }

    private void cancel(Bill bill) {
        BillStatus previous = bill.status;
        bill.status = BillStatus.CANCELLED;
        bill.cancellationReason = DEBT_BALANCE_CHANGED;
        audit.record("Bill", bill.id, "BILL_CANCELLED_AFTER_BALANCE_CHANGE", previous, bill);
    }
}
