package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

@Service
class PaymentAllocationService {
    private final PaymentRepository payments;
    private final InstallmentRepository installments;
    private final PaymentPlanRepository plans;
    private final CreditBalanceService credits;
    private final PaymentDebtAllocator debtAllocator;
    private final PaymentInstallmentAllocator installmentAllocator;
    private final EventosDeDominio eventos;
    private final AuditService audit;
    private final BillLifecycleService billLifecycle;

    PaymentAllocationService(PaymentRepository payments, InstallmentRepository installments, PaymentPlanRepository plans,
        CreditBalanceService credits, PaymentDebtAllocator debtAllocator, PaymentInstallmentAllocator installmentAllocator,
        EventosDeDominio eventos, AuditService audit, BillLifecycleService billLifecycle) {
        this.payments = payments;
        this.installments = installments;
        this.plans = plans;
        this.credits = credits;
        this.debtAllocator = debtAllocator;
        this.installmentAllocator = installmentAllocator;
        this.eventos = eventos;
        this.audit = audit;
        this.billLifecycle = billLifecycle;
    }

    @Transactional
    void applyInitial(Payment payment, List<ApiDtos.AllocationRequest> requests) {
        List<ApiDtos.AllocationRequest> allocationsToApply = requests == null ? List.of() : requests;
        lockPlans(allocationsToApply);
        List<Long> affectedDebtIds = debtIds(allocationsToApply);
        List<Bill> lockedBills = billLifecycle.lockForPayment(payment, requests == null ? List.of() : requests);
        if (requests != null) for (ApiDtos.AllocationRequest request : requests) allocate(payment, request);
        BigDecimal remaining = payment.amount.subtract(payment.allocatedAmount);
        payment.unallocatedAmount = remaining;
        payment.allocationStatus = payment.allocatedAmount.signum() == 0 ? PaymentAllocationStatus.UNALLOCATED
            : remaining.signum() == 0 ? PaymentAllocationStatus.FULLY_ALLOCATED : PaymentAllocationStatus.PARTIALLY_ALLOCATED;
        if (remaining.signum() > 0 && requests != null && !requests.isEmpty()) credits.createFromPayment(payment, remaining);
        eventos.pagoRegistrado(payment);
        audit.record("Payment", payment.id, "PAYMENT_REGISTERED", payment);
        billLifecycle.cancelForDebtBalanceChange(affectedDebtIds, lockedBills);
    }

    @Transactional
    PaymentAllocation allocateExisting(Long paymentId, ApiDtos.AllocationRequest request, String ifMatch) {
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow(() -> CatalogService.notFound("Pago"));
        CatalogService.require(payment.status == PaymentStatus.CONFIRMED, "PAYMENT_NOT_ALLOCATABLE", "El pago no admite imputaciones");
        credits.requireNoPaymentCredit(paymentId);
        lockPlans(List.of(request));
        List<Long> affectedDebtIds = debtIds(List.of(request));
        List<Bill> lockedBills = billLifecycle.lockForPayment(payment, List.of(request));
        if (ifMatch != null) {
            if (request.installmentId() != null) installmentAllocator.requireIfMatch(request, ifMatch);
            else debtAllocator.requireIfMatch(request, ifMatch);
        }
        PaymentAllocation allocation = allocate(payment, request);
        payment.unallocatedAmount = payment.amount.subtract(payment.allocatedAmount);
        payment.allocationStatus = payment.unallocatedAmount.signum() == 0 ? PaymentAllocationStatus.FULLY_ALLOCATED
            : PaymentAllocationStatus.PARTIALLY_ALLOCATED;
        billLifecycle.cancelForDebtBalanceChange(affectedDebtIds, lockedBills);
        return allocation;
    }

    private List<Long> debtIds(List<ApiDtos.AllocationRequest> requests) {
        if (requests == null) return List.of();
        java.util.Set<Long> ids = new java.util.TreeSet<>();
        for (ApiDtos.AllocationRequest request : requests) {
            if (request.debtId() != null) ids.add(request.debtId());
            if (request.installmentId() != null) {
                Long planId = installments.findPaymentPlanIdById(request.installmentId())
                    .orElseThrow(() -> CatalogService.notFound("Cuota"));
                billLifecycle.planDebtIds(planId).forEach(ids::add);
            }
        }
        return List.copyOf(ids);
    }

    private void lockPlans(List<ApiDtos.AllocationRequest> requests) {
        requests.stream().filter(request -> request.installmentId() != null)
            .map(request -> installments.findPaymentPlanIdById(request.installmentId())
                .orElseThrow(() -> CatalogService.notFound("Cuota")))
            .distinct().sorted().forEach(id -> plans.findByIdForUpdate(id)
                .orElseThrow(() -> CatalogService.notFound("Plan")));
    }

    private PaymentAllocation allocate(Payment payment, ApiDtos.AllocationRequest request) {
        CatalogService.require((request.debtId() == null) != (request.installmentId() == null),
            "INVALID_ALLOCATION_TARGET", "Debe indicar una deuda o una cuota");
        return request.installmentId() != null ? installmentAllocator.allocate(payment, request)
            : debtAllocator.allocate(payment, request);
    }
}
