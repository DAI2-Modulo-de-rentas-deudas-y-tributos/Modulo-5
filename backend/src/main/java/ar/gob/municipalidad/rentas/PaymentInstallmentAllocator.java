package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
class PaymentInstallmentAllocator {
    private final InstallmentRepository installments;
    private final PaymentPlanRepository plans;
    private final DebtRepository debts;
    private final PaymentAllocationRepository allocations;
    private final PaymentPlanDebtRepository planDebts;
    private final PaymentPlanConfigurationRepository planConfigurations;
    private final CurrentIdentity identity;
    private final AuditService audit;
    private final ConfirmedIntegrationOutbox confirmedEvents;
    private final EventosDeDominio eventos;
    private final PaymentPlanPrincipalLedger principalLedger;

    PaymentInstallmentAllocator(InstallmentRepository installments, PaymentPlanRepository plans,
        DebtRepository debts, PaymentAllocationRepository allocations, PaymentPlanDebtRepository planDebts,
        PaymentPlanConfigurationRepository planConfigurations, CurrentIdentity identity, AuditService audit,
        ConfirmedIntegrationOutbox confirmedEvents, EventosDeDominio eventos,
        PaymentPlanPrincipalLedger principalLedger) {
        this.installments = installments;
        this.plans = plans;
        this.debts = debts;
        this.allocations = allocations;
        this.planDebts = planDebts;
        this.planConfigurations = planConfigurations;
        this.identity = identity;
        this.audit = audit;
        this.confirmedEvents = confirmedEvents;
        this.eventos = eventos;
        this.principalLedger = principalLedger;
    }

    PaymentAllocation allocate(Payment payment, ApiDtos.AllocationRequest request) {
        Long planId = installments.findPaymentPlanIdById(request.installmentId()).orElseThrow(() -> CatalogService.notFound("Cuota"));
        PaymentPlan plan = plans.findByIdForUpdate(planId).orElseThrow(() -> CatalogService.notFound("Plan"));
        Installment installment = installments.findByIdForUpdate(request.installmentId()).orElseThrow(() -> CatalogService.notFound("Cuota"));
        CatalogService.require(plan.taxpayerId.equals(payment.taxpayerId), "PAYMENT_TAXPAYER_MISMATCH", "El pago y el plan pertenecen a contribuyentes distintos");
        CatalogService.require(plan.status == PaymentPlanStatus.ACTIVE && installment.status != InstallmentStatus.PAID
            && installment.status != InstallmentStatus.CANCELLED, "INSTALLMENT_NOT_PAYABLE", "La cuota no admite pagos");

        BigDecimal available = payment.amount.subtract(payment.allocatedAmount);
        BigDecimal requested = PaymentService.money(request.amount());
        BigDecimal applied = requested.min(available).min(installment.outstandingAmount);
        CatalogService.require(applied.signum() > 0, "NO_PAYMENT_BALANCE", "No hay saldo disponible para imputar");
        PaymentPlanConfiguration configuration = planConfigurations.findById(plan.configurationId)
            .orElseThrow(() -> CatalogService.notFound("Configuración de planes"));
        CatalogService.require(applied.compareTo(installment.outstandingAmount) >= 0 || configuration.partialInstallmentPaymentAllowed,
            "PARTIAL_INSTALLMENT_PAYMENT_NOT_ALLOWED", "La configuración no permite pago parcial de cuota");

        BigDecimal oldPaid = installment.paidAmount;
        BigDecimal newPaid = oldPaid.add(applied);
        BigDecimal principalDelta = principalRecognized(installment, newPaid).subtract(principalRecognized(installment, oldPaid));
        installment.paidAmount = newPaid;
        installment.outstandingAmount = installment.totalAmount.subtract(newPaid);
        installment.status = installment.outstandingAmount.signum() == 0 ? InstallmentStatus.PAID : InstallmentStatus.PARTIALLY_PAID;
        if (installment.status == InstallmentStatus.PAID) installment.paidAt = OffsetDateTime.now();

        PaymentAllocation allocation = new PaymentAllocation();
        allocation.paymentId = payment.id;
        allocation.targetType = AllocationTargetType.INSTALLMENT;
        allocation.installmentId = installment.id;
        allocation.amount = applied;
        allocation.principalApplied = principalDelta;
        allocation.interestApplied = applied.subtract(principalDelta);
        allocation.status = "ACTIVE";
        allocation.allocatedBy = identity.get().userId();
        allocation.allocatedAt = OffsetDateTime.now();
        allocations.save(allocation);

        List<PaymentPlanPrincipalLedger.Share> shares = distributePrincipal(plan, principalDelta);
        principalLedger.record(allocation, shares);
        for (PaymentPlanPrincipalLedger.Share share : shares) {
            Debt debt = debts.findById(share.debtId()).orElseThrow();
            confirmedEvents.paymentRegistered(payment, allocation, debt, share.amount());
            if (debt.status == DebtStatus.PAID) eventos.deudaSaldada(debt, allocation);
        }

        plan.paidAmount = plan.paidAmount.add(applied);
        plan.outstandingPlanAmount = plan.totalPlanAmount.subtract(plan.paidAmount);
        payment.allocatedAmount = payment.allocatedAmount.add(applied);
        payment.unallocatedAmount = payment.amount.subtract(payment.allocatedAmount);
        if (installments.findByPaymentPlanIdOrderByNumber(plan.id).stream().allMatch(item -> item.status == InstallmentStatus.PAID)) {
            plan.status = PaymentPlanStatus.COMPLETED;
            plan.completedAt = OffsetDateTime.now();
            planDebts.findByPaymentPlanId(plan.id).forEach(link -> link.status = PaymentPlanDebtStatus.SETTLED);
            eventos.planCompletado(plan);
        }
        audit.record("PaymentAllocation", allocation.id, "INSTALLMENT_PAYMENT_ALLOCATED", allocation);
        return allocation;
    }

    void requireIfMatch(ApiDtos.AllocationRequest request, String ifMatch) {
        Long planId = installments.findPaymentPlanIdById(request.installmentId()).orElseThrow(() -> CatalogService.notFound("Cuota"));
        PaymentPlan plan = plans.findByIdForUpdate(planId).orElseThrow(() -> CatalogService.notFound("Plan"));
        EntityTags.requireIfMatch(EntityTags.paymentPlan(ApiResponses.of(plan)), ifMatch);
    }

    private BigDecimal principalRecognized(Installment installment, BigDecimal paid) {
        if (paid.signum() == 0) return MonetaryAmounts.zero();
        if (paid.compareTo(installment.totalAmount) >= 0) return installment.principalAmount;
        return installment.principalAmount.multiply(paid).divide(installment.totalAmount, 2, RoundingMode.HALF_UP);
    }

    private List<PaymentPlanPrincipalLedger.Share> distributePrincipal(PaymentPlan plan, BigDecimal principal) {
        if (principal.signum() == 0) return List.of();
        List<PaymentPlanDebt> links = planDebts.findByPaymentPlanId(plan.id).stream()
            .filter(link -> link.status == PaymentPlanDebtStatus.ACTIVE).toList();
        BigDecimal total = links.stream().map(link -> link.remainingPrincipalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        CatalogService.require(total.signum() > 0 && principal.compareTo(total) <= 0,
            "PLAN_PRINCIPAL_INVALID", "El capital a distribuir supera el saldo del plan");
        List<PaymentPlanPrincipalLedger.Share> shares = new ArrayList<>();
        BigDecimal distributed = MonetaryAmounts.zero();
        for (int index = 0; index < links.size(); index++) {
            PaymentPlanDebt link = links.get(index);
            BigDecimal share = index == links.size() - 1 ? principal.subtract(distributed)
                : principal.multiply(link.remainingPrincipalAmount).divide(total, 2, RoundingMode.DOWN);
            share = share.min(link.remainingPrincipalAmount);
            if (share.signum() == 0) continue;
            link.principalPaidAmount = link.principalPaidAmount.add(share);
            link.remainingPrincipalAmount = link.remainingPrincipalAmount.subtract(share);
            if (link.remainingPrincipalAmount.signum() == 0) link.status = PaymentPlanDebtStatus.SETTLED;
            Debt debt = debts.findByIdForUpdate(link.debtId).orElseThrow();
            debt.imputar(share);
            debt.updatedAt = OffsetDateTime.now();
            distributed = distributed.add(share);
            shares.add(new PaymentPlanPrincipalLedger.Share(debt.id, share));
        }
        CatalogService.require(distributed.compareTo(principal) == 0, "PLAN_PRINCIPAL_INVALID", "El capital de la cuota no se distribuyó por completo");
        return shares;
    }
}
