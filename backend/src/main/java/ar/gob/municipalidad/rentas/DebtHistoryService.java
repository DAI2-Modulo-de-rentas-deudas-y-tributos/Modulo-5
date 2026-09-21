package ar.gob.municipalidad.rentas;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DebtHistoryService {
    private final DebtRepository debts;
    private final LiquidationRepository liquidations;
    private final ExternalObligationRepository externalObligations;
    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final CreditBalanceApplicationRepository creditApplications;
    private final PaymentPlanRepository plans;
    private final PaymentPlanDebtRepository planDebts;
    private final InstallmentRepository installments;
    private final AdjustmentRepository adjustments;
    private final AuditRepository audit;
    private final CurrentIdentity identity;

    DebtHistoryService(DebtRepository debts,LiquidationRepository liquidations,ExternalObligationRepository externalObligations,
        PaymentRepository payments,PaymentAllocationRepository allocations,CreditBalanceApplicationRepository creditApplications,
        PaymentPlanRepository plans,PaymentPlanDebtRepository planDebts,InstallmentRepository installments,
        AdjustmentRepository adjustments,AuditRepository audit,CurrentIdentity identity) {
        this.debts=debts;this.liquidations=liquidations;this.externalObligations=externalObligations;this.payments=payments;
        this.allocations=allocations;this.creditApplications=creditApplications;this.plans=plans;this.planDebts=planDebts;
        this.installments=installments;this.adjustments=adjustments;this.audit=audit;this.identity=identity;
    }

    @Transactional(readOnly=true)
    ApiDtos.DebtHistoryResponse get(Long debtId) {
        Debt debt=debts.findById(debtId).orElseThrow(()->CatalogService.notFound("Deuda"));
        identity.requireOwnership(debt.taxpayerId);
        String originId=originId(debt);
        List<ApiDtos.DebtHistoryEntryResponse> entries=new ArrayList<>();
        entries.add(entry("DEBT_CREATED",debt.createdAt,debt.originType.name(),originId,null,null,debt.originalAmount,"PENDING","CREATED"));

        LinkedHashMap<Long,PaymentPlan> relatedPlans=new LinkedHashMap<>();
        plans.findByDebtIdOrderByGrantedAtAscIdAsc(debt.id).forEach(plan->relatedPlans.put(plan.id,plan));
        List<PaymentPlanDebt> links=planDebts.findByDebtIdOrderByCreatedAtAscIdAsc(debt.id);
        if(!links.isEmpty()) plans.findAllById(links.stream().map(link->link.paymentPlanId).distinct().toList()).forEach(plan->relatedPlans.put(plan.id,plan));
        Map<Long,PaymentPlanDebt> linksByPlan=new HashMap<>();
        links.forEach(link->linksByPlan.put(link.paymentPlanId,link));
        relatedPlans.values().forEach(plan->{
            PaymentPlanDebt link=linksByPlan.get(plan.id);
            entries.add(entry("PAYMENT_PLAN_LINKED",link==null?plan.grantedAt:link.createdAt,"PAYMENT_PLAN",plan.id.toString(),
                link==null?null:"PAYMENT_PLAN_DEBT",link==null?null:link.id.toString(),link==null?plan.originalPrincipalAmount:link.includedPrincipalAmount,plan.status.name(),"LINKED"));
        });

        List<PaymentAllocation> relatedAllocations=new ArrayList<>(allocations.findByDebtIdOrderByAllocatedAtAscIdAsc(debt.id));
        if(!relatedPlans.isEmpty()) {
            List<Long> installmentIds=installments.findByPaymentPlanIdInOrderByPaymentPlanIdAscNumberAsc(relatedPlans.keySet()).stream().map(i->i.id).toList();
            if(!installmentIds.isEmpty()) relatedAllocations.addAll(allocations.findByInstallmentIdInOrderByAllocatedAtAscIdAsc(installmentIds));
        }
        relatedAllocations.sort(Comparator.comparing((PaymentAllocation a)->a.allocatedAt).thenComparing(a->a.id));
        Map<Long,Payment> relatedPayments=new HashMap<>();
        if(!relatedAllocations.isEmpty()) payments.findAllById(relatedAllocations.stream().map(a->a.paymentId).distinct().toList()).forEach(p->relatedPayments.put(p.id,p));
        relatedPayments.values().stream().sorted(Comparator.comparing((Payment p)->p.paidAt).thenComparing(p->p.id)).forEach(payment->
            entries.add(entry("PAYMENT_REGISTERED",payment.paidAt,"PAYMENT",payment.id.toString(),null,null,payment.amount,PaymentStatus.CONFIRMED.name(),"REGISTERED")));
        relatedAllocations.forEach(allocation->{
            entries.add(entry("PAYMENT_ALLOCATION",allocation.allocatedAt,"PAYMENT_ALLOCATION",allocation.id.toString(),"PAYMENT",allocation.paymentId.toString(),allocation.amount,"ACTIVE","ALLOCATED"));
            if(allocation.reversedAt!=null) entries.add(entry("PAYMENT_ALLOCATION_REVERSED",allocation.reversedAt,"PAYMENT_ALLOCATION",allocation.id.toString(),"PAYMENT",allocation.paymentId.toString(),allocation.amount,"REVERSED","REVERSED"));
        });

        creditApplications.findByDebtIdOrderByAppliedAtAscIdAsc(debt.id).forEach(application->{
            entries.add(entry("CREDIT_BALANCE_APPLIED",application.appliedAt,"CREDIT_BALANCE_APPLICATION",application.id.toString(),"CREDIT_BALANCE",application.creditBalanceId.toString(),application.amount,"ACTIVE","APPLIED"));
            if(application.reversedAt!=null) entries.add(entry("CREDIT_BALANCE_APPLICATION_REVERSED",application.reversedAt,"CREDIT_BALANCE_APPLICATION",application.id.toString(),"CREDIT_BALANCE",application.creditBalanceId.toString(),application.amount,"REVERSED","REVERSED"));
        });

        adjustments.findByDebtIdOrderByRequestedAtAscIdAsc(debt.id).forEach(adjustment->{
            entries.add(entry("DEBT_ADJUSTMENT_REQUESTED",adjustment.requestedAt,"ADJUSTMENT",adjustment.id.toString(),null,null,adjustment.amount,AdjustmentStatus.PENDING_APPROVAL.name(),adjustment.type.name()));
            if(adjustment.resolvedAt!=null) entries.add(entry("DEBT_ADJUSTMENT_RESOLVED",adjustment.resolvedAt,"ADJUSTMENT",adjustment.id.toString(),null,null,adjustment.amount,adjustment.status.name(),adjustment.type.name()));
        });

        audit.findByEntityTypeAndEntityIdOrderByOccurredAt("Debt",debt.id.toString()).forEach(item->
            entries.add(entry("DEBT_CHANGE",item.occurredAt,"DEBT",debt.id.toString(),null,null,null,null,item.action)));
        entries.sort(Comparator.comparing(ApiDtos.DebtHistoryEntryResponse::date)
            .thenComparing(ApiDtos.DebtHistoryEntryResponse::type)
            .thenComparing(ApiDtos.DebtHistoryEntryResponse::referenceId,Comparator.nullsFirst(String::compareTo)));
        return new ApiDtos.DebtHistoryResponse(debt.id,debt.originType,originId,"ASC",List.copyOf(entries));
    }

    private String originId(Debt debt) {
        if(debt.originType==DebtOriginType.LIQUIDATION) {
            liquidations.findById(debt.liquidationId).orElseThrow(()->CatalogService.notFound("Liquidación"));
            return debt.liquidationId.toString();
        }
        ExternalObligation obligation=externalObligations.findById(debt.externalObligationId).orElseThrow(()->CatalogService.notFound("Obligación externa"));
        return obligation.externalReferenceId;
    }

    private ApiDtos.DebtHistoryEntryResponse entry(String type,OffsetDateTime date,String referenceType,String referenceId,
        String relatedReferenceType,String relatedReferenceId,BigDecimal amount,String status,String action) {
        return new ApiDtos.DebtHistoryEntryResponse(type,date,referenceType,referenceId,relatedReferenceType,relatedReferenceId,amount,status,action);
    }
}
