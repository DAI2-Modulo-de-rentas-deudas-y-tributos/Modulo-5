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

    PaymentAllocationService(PaymentRepository payments, InstallmentRepository installments, PaymentPlanRepository plans,
        CreditBalanceService credits, PaymentDebtAllocator debtAllocator, PaymentInstallmentAllocator installmentAllocator,
        EventosDeDominio eventos, AuditService audit) {
        this.payments = payments;
        this.installments = installments;
        this.plans = plans;
        this.credits = credits;
        this.debtAllocator = debtAllocator;
        this.installmentAllocator = installmentAllocator;
        this.eventos = eventos;
        this.audit = audit;
    }

    @Transactional
    void applyInitial(Payment payment, List<ApiDtos.AllocationRequest> requests) {
        if (requests != null) {
            // Al pagar cuotas de varios planes, bloquear siempre en el mismo orden.
            requests.stream().filter(request -> request.installmentId() != null)
                .map(request -> installments.findPaymentPlanIdById(request.installmentId())
                    .orElseThrow(() -> CatalogService.notFound("Cuota")))
                .distinct().sorted().forEach(id -> plans.findByIdForUpdate(id)
                    .orElseThrow(() -> CatalogService.notFound("Plan")));
            for (ApiDtos.AllocationRequest request : requests) allocate(payment, request);
        }
        BigDecimal remaining = payment.amount.subtract(payment.allocatedAmount);
        payment.unallocatedAmount = remaining;
        payment.allocationStatus = payment.allocatedAmount.signum() == 0 ? PaymentAllocationStatus.UNALLOCATED
            : remaining.signum() == 0 ? PaymentAllocationStatus.FULLY_ALLOCATED : PaymentAllocationStatus.PARTIALLY_ALLOCATED;
        if (remaining.signum() > 0 && requests != null && !requests.isEmpty()) credits.createFromPayment(payment, remaining);
        eventos.pagoRegistrado(payment);
        audit.record("Payment", payment.id, "PAYMENT_REGISTERED", payment);
    }

    @Transactional
    PaymentAllocation allocateExisting(Long paymentId, ApiDtos.AllocationRequest request, String ifMatch) {
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow(() -> CatalogService.notFound("Pago"));
        CatalogService.require(payment.status == PaymentStatus.CONFIRMED, "PAYMENT_NOT_ALLOCATABLE", "El pago no admite imputaciones");
        credits.requireNoPaymentCredit(paymentId);
        if (ifMatch != null) {
            if (request.installmentId() != null) installmentAllocator.requireIfMatch(request, ifMatch);
            else debtAllocator.requireIfMatch(request, ifMatch);
        }
        PaymentAllocation allocation = allocate(payment, request);
        payment.unallocatedAmount = payment.amount.subtract(payment.allocatedAmount);
        payment.allocationStatus = payment.unallocatedAmount.signum() == 0 ? PaymentAllocationStatus.FULLY_ALLOCATED
            : PaymentAllocationStatus.PARTIALLY_ALLOCATED;
        return allocation;
    }

    private PaymentAllocation allocate(Payment payment, ApiDtos.AllocationRequest request) {
        CatalogService.require((request.debtId() == null) != (request.installmentId() == null),
            "INVALID_ALLOCATION_TARGET", "Debe indicar una deuda o una cuota");
        return request.installmentId() != null ? installmentAllocator.allocate(payment, request)
            : debtAllocator.allocate(payment, request);
    }
}
