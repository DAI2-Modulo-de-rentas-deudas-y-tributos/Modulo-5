package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Service
class PaymentDebtAllocator {
    private final DebtRepository debts;
    private final PaymentAllocationRepository allocations;
    private final TaxConfigurationRepository configurations;
    private final PaymentPlanRepository plans;
    private final AuditService audit;
    private final CurrentIdentity identity;
    private final BillDebtRepository billDebts;
    private final ConfirmedIntegrationOutbox confirmedEvents;
    private final EventosDeDominio eventos;
    private final PaymentPlanDebtRepository planDebts;

    PaymentDebtAllocator(DebtRepository debts, PaymentAllocationRepository allocations,
        TaxConfigurationRepository configurations, PaymentPlanRepository plans, AuditService audit,
        CurrentIdentity identity, BillDebtRepository billDebts, ConfirmedIntegrationOutbox confirmedEvents,
        EventosDeDominio eventos, PaymentPlanDebtRepository planDebts) {
        this.debts = debts;
        this.allocations = allocations;
        this.configurations = configurations;
        this.plans = plans;
        this.audit = audit;
        this.identity = identity;
        this.billDebts = billDebts;
        this.confirmedEvents = confirmedEvents;
        this.eventos = eventos;
        this.planDebts = planDebts;
    }

    PaymentAllocation allocate(Payment payment, ApiDtos.AllocationRequest request) {
        Debt debt = debts.findByIdForUpdate(request.debtId()).orElseThrow(() -> CatalogService.notFound("Deuda"));
        if (payment.billId != null) CatalogService.require(billDebts.findByBillId(payment.billId).stream()
            .anyMatch(link -> link.debtId.equals(debt.id)), "DEBT_NOT_IN_BILL", "La deuda no pertenece a la boleta informada");
        CatalogService.require(debt.taxpayerId.equals(payment.taxpayerId), "PAYMENT_TAXPAYER_MISMATCH", "El pago y la deuda pertenecen a contribuyentes distintos");
        CatalogService.requireConflict(!plans.existsByDebtIdAndStatus(debt.id, PaymentPlanStatus.ACTIVE)
            && !planDebts.existsByDebtIdAndStatus(debt.id, PaymentPlanDebtStatus.ACTIVE),
            "DEBT_IN_ACTIVE_PAYMENT_PLAN", "La deuda se encuentra incluida en un plan de pago activo");
        CatalogService.requireConflict(debt.admiteCobro(), "DEBT_NOT_PAYABLE", "La deuda no admite pagos");

        BigDecimal available = payment.amount.subtract(payment.allocatedAmount);
        BigDecimal requested = PaymentService.money(request.amount());
        BigDecimal applied = requested.min(available).min(debt.outstandingBalance);
        CatalogService.require(applied.signum() > 0, "NO_PAYMENT_BALANCE", "No hay saldo disponible para imputar");
        if (applied.compareTo(debt.outstandingBalance) < 0 && debt.configurationId != null) {
            TaxConfiguration configuration = configurations.findById(debt.configurationId).orElseThrow();
            CatalogService.require(configuration.partialPaymentAllowed, "PARTIAL_PAYMENT_NOT_ALLOWED", "El concepto no permite pagos parciales");
        }

        PaymentAllocation allocation = new PaymentAllocation();
        allocation.paymentId = payment.id;
        allocation.targetType = AllocationTargetType.DEBT;
        allocation.debtId = debt.id;
        allocation.amount = applied;
        allocation.principalApplied = applied;
        allocation.interestApplied = MonetaryAmounts.zero();
        allocation.status = "ACTIVE";
        allocation.allocatedBy = identity.get().userId();
        allocation.allocatedAt = OffsetDateTime.now();
        allocations.save(allocation);
        payment.allocatedAmount = payment.allocatedAmount.add(applied);
        payment.unallocatedAmount = payment.amount.subtract(payment.allocatedAmount);
        debt.imputar(applied);
        debt.updatedAt = OffsetDateTime.now();
        audit.record("PaymentAllocation", allocation.id, "PAYMENT_ALLOCATED", allocation);
        confirmedEvents.paymentRegistered(payment, allocation, debt);
        if (debt.status == DebtStatus.PAID) eventos.deudaSaldada(debt, allocation);
        return allocation;
    }

    void requireIfMatch(ApiDtos.AllocationRequest request, String ifMatch) {
        Debt debt = debts.findByIdForUpdate(request.debtId()).orElseThrow(() -> CatalogService.notFound("Deuda"));
        boolean inPlan = plans.existsByDebtIdAndStatus(debt.id, PaymentPlanStatus.ACTIVE)
            || planDebts.existsByDebtIdAndStatus(debt.id, PaymentPlanDebtStatus.ACTIVE);
        EntityTags.requireIfMatch(EntityTags.debt(ApiResponses.of(debt, inPlan)), ifMatch);
    }
}
