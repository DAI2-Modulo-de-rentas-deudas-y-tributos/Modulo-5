package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.Page;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
class ReversalService {
    private final PaymentReversalRepository reversals; private final PaymentRepository payments; private final PaymentAllocationRepository allocations; private final DebtRepository debts; private final CreditBalanceRepository credits; private final AuditService audit; private final CurrentIdentity identity;
    private final ConfirmedIntegrationOutbox events;
    private final InstallmentRepository installments;private final PaymentPlanRepository plans;private final PaymentPlanDebtRepository planDebts;private final PaymentPlanPrincipalLedger principalLedger;private final ReversalRejectionAudit rejectionAudit;
    private final BillLifecycleService billLifecycle;
    ReversalService(PaymentReversalRepository reversals,PaymentRepository payments,PaymentAllocationRepository allocations,DebtRepository debts,CreditBalanceRepository credits,AuditService audit,CurrentIdentity identity,InstallmentRepository installments,PaymentPlanRepository plans,PaymentPlanDebtRepository planDebts,ConfirmedIntegrationOutbox events,PaymentPlanPrincipalLedger principalLedger,ReversalRejectionAudit rejectionAudit,BillLifecycleService billLifecycle){this.reversals=reversals;this.payments=payments;this.allocations=allocations;this.debts=debts;this.credits=credits;this.audit=audit;this.identity=identity;this.installments=installments;this.plans=plans;this.planDebts=planDebts;this.events=events;this.principalLedger=principalLedger;this.rejectionAudit=rejectionAudit;this.billLifecycle=billLifecycle;}
    @Transactional PaymentReversalRequest request(Long paymentId,String reason){return request(paymentId,reason,null);}
    @Transactional PaymentReversalRequest request(Long paymentId,String reason,String ifMatch){Payment p=payments.findByIdForUpdate(paymentId).orElseThrow(()->CatalogService.notFound("Pago"));EntityTags.requireIfMatch(EntityTags.payment(ApiResponses.of(p)),ifMatch);CatalogService.require(p.status==PaymentStatus.CONFIRMED,"PAYMENT_NOT_REVERSIBLE","El pago no puede revertirse");CatalogService.require(!reversals.existsByPaymentIdAndStatusIn(paymentId,List.of(PaymentReversalStatus.PENDING_APPROVAL,PaymentReversalStatus.APPROVED)),"REVERSAL_ALREADY_EXISTS","El pago ya tiene una reversión pendiente");validateGeneratedCreditIsUnused(p.id);PaymentReversalRequest r=new PaymentReversalRequest();r.paymentId=paymentId;r.reason=reason;r.status=PaymentReversalStatus.PENDING_APPROVAL;r.requestedBy=identity.get().userId();r.requestedAt=OffsetDateTime.now();reversals.save(r);audit.record("PaymentReversal",r.id,"PAYMENT_REVERSAL_REQUESTED",r);return r;}
    @Transactional PaymentReversalRequest approve(Long id){return approve(id,null);}
    @Transactional PaymentReversalRequest approve(Long id,String ifMatch){PaymentReversalRequest r=reversals.findByIdForUpdate(id).orElseThrow(()->CatalogService.notFound("Solicitud de reversión"));EntityTags.requireIfMatch(EntityTags.paymentReversal(ApiResponses.of(r)),ifMatch);CatalogService.require(r.status==PaymentReversalStatus.PENDING_APPROVAL,"REVERSAL_NOT_PENDING","La reversión no está pendiente");audit.requireDifferentActor("PaymentReversal",r.id,r.requestedBy);r.status=PaymentReversalStatus.APPROVED;r.resolvedBy=identity.get().userId();r.resolvedAt=OffsetDateTime.now();audit.record("PaymentReversal",r.id,"PAYMENT_REVERSAL_APPROVED",r);return r;}
    @Transactional PaymentReversalRequest reject(Long id,String reason){return reject(id,reason,null);}
    @Transactional PaymentReversalRequest reject(Long id,String reason,String ifMatch){PaymentReversalRequest r=reversals.findByIdForUpdate(id).orElseThrow(()->CatalogService.notFound("Solicitud de reversión"));EntityTags.requireIfMatch(EntityTags.paymentReversal(ApiResponses.of(r)),ifMatch);CatalogService.require(r.status==PaymentReversalStatus.PENDING_APPROVAL,"REVERSAL_NOT_PENDING","La reversión no está pendiente");audit.requireDifferentActor("PaymentReversal",r.id,r.requestedBy);CatalogService.require(reason!=null&&!reason.isBlank(),"INVALID_REQUEST","El motivo de rechazo es obligatorio");r.status=PaymentReversalStatus.REJECTED;r.resolutionReason=reason;r.resolvedBy=identity.get().userId();r.resolvedAt=OffsetDateTime.now();audit.record("PaymentReversal",r.id,"PAYMENT_REVERSAL_REJECTED",r);return r;}
    @Transactional PaymentReversalRequest execute(Long id){return execute(id,null);}
    @Transactional PaymentReversalRequest execute(Long id,String ifMatch){PaymentReversalRequest r=get(id,ifMatch);CatalogService.require(r.status==PaymentReversalStatus.APPROVED,"REVERSAL_NOT_APPROVED","La reversión debe estar aprobada");Payment p=payments.findByIdForUpdate(r.paymentId).orElseThrow();CatalogService.require(p.status==PaymentStatus.CONFIRMED,"PAYMENT_NOT_REVERSIBLE","El pago no puede revertirse");List<PaymentAllocation> active=allocations.findByPaymentId(p.id);validateInstallmentReversals(r,active);List<Long> changedDebtIds=debtIdsRestoredBy(active);List<Bill> affectedBills=billLifecycle.lockForDebtBalanceChange(changedDebtIds);validateGeneratedCreditIsUnused(p.id).ifPresent(c->{c.availableAmount=MonetaryAmounts.zero();c.status=CreditBalanceStatus.CANCELLED;c.updatedAt=OffsetDateTime.now();});for(PaymentAllocation a:active){if(!a.status.equals("ACTIVE"))continue;if(a.targetType==AllocationTargetType.INSTALLMENT)reverseInstallment(p,a,r.reason);else{Debt d=debts.findByIdForUpdate(a.debtId).orElseThrow();d.revertirImputacion(a.amount);d.updatedAt=OffsetDateTime.now();a.reversedAt=d.updatedAt;events.paymentReversed(p,a,d,r.reason);}a.status="REVERSED";if(a.reversedAt==null)a.reversedAt=OffsetDateTime.now();}billLifecycle.cancelForDebtBalanceChange(changedDebtIds,affectedBills);p.status=PaymentStatus.REVERSED;r.status=PaymentReversalStatus.EXECUTED;r.executedBy=identity.get().userId();r.executedAt=OffsetDateTime.now();audit.record("Payment",p.id,"PAYMENT_REVERSED",p);return r;}
    private List<Long> debtIdsRestoredBy(List<PaymentAllocation> paymentAllocations){Set<Long> debtIds=new TreeSet<>();for(PaymentAllocation allocation:paymentAllocations){if(allocation.targetType==AllocationTargetType.DEBT)debtIds.add(allocation.debtId);else if(allocation.principalApplied.signum()>0){Optional<List<PaymentPlanPrincipalLedger.Share>> shares=principalLedger.find(allocation);if(shares.isPresent())shares.orElseThrow().forEach(share->debtIds.add(share.debtId()));else{Long planId=installments.findPaymentPlanIdById(allocation.installmentId).orElseThrow(()->CatalogService.notFound("Cuota"));planDebts.findByPaymentPlanId(planId).forEach(link->debtIds.add(link.debtId));}}}return List.copyOf(debtIds);}
    private void validateInstallmentReversals(PaymentReversalRequest reversal,List<PaymentAllocation> allocationsToReverse){
        CatalogService.require(allocationsToReverse.stream().allMatch(a->"ACTIVE".equals(a.status)),"ALLOCATION_NOT_REVERSIBLE","Una imputación del pago ya no está activa");
        List<PaymentAllocation> installmentAllocations=allocationsToReverse.stream().filter(a->a.targetType==AllocationTargetType.INSTALLMENT).toList();
        Map<Long,Long> planIds=new HashMap<>();
        for(PaymentAllocation allocation:installmentAllocations){
            CatalogService.require(allocation.installmentId!=null,"PLAN_DISTRIBUTION_INVALID","La imputación no identifica una cuota");
            planIds.put(allocation.installmentId,installments.findPaymentPlanIdById(allocation.installmentId).orElseThrow(()->CatalogService.notFound("Cuota")));
        }
        Map<Long,PaymentPlan> lockedPlans=new HashMap<>();
        planIds.values().stream().distinct().sorted().forEach(planId->lockedPlans.put(planId,plans.findByIdForUpdate(planId).orElseThrow()));
        Map<Long,Installment> lockedInstallments=new HashMap<>();
        planIds.keySet().stream().sorted().forEach(installmentId->lockedInstallments.put(installmentId,installments.findByIdForUpdate(installmentId).orElseThrow()));
        Map<Long,BigDecimal> amountByInstallment=new HashMap<>();Map<Long,BigDecimal> amountByPlan=new HashMap<>();
        for(PaymentAllocation allocation:installmentAllocations){
            amountByInstallment.merge(allocation.installmentId,allocation.amount,BigDecimal::add);
            amountByPlan.merge(planIds.get(allocation.installmentId),allocation.amount,BigDecimal::add);
        }
        for(PaymentAllocation allocation:installmentAllocations){
            PaymentPlan plan=lockedPlans.get(planIds.get(allocation.installmentId));Installment installment=lockedInstallments.get(allocation.installmentId);
            if(installment.status==InstallmentStatus.CANCELLED)block(reversal,allocation,"INSTALLMENT_CANCELLED","Una cuota cancelada no puede revertirse");
            if(plan.status!=PaymentPlanStatus.ACTIVE&&plan.status!=PaymentPlanStatus.COMPLETED)block(reversal,allocation,"PLAN_NOT_REVERSIBLE","El estado del plan no admite revertir cuotas");
            if(installment.status!=InstallmentStatus.PAID&&installment.status!=InstallmentStatus.PARTIALLY_PAID)block(reversal,allocation,"INSTALLMENT_NOT_REVERSIBLE","La cuota no registra un pago reversible");
            if(installment.paidAmount.compareTo(amountByInstallment.get(installment.id))<0||plan.paidAmount.compareTo(amountByPlan.get(plan.id))<0)block(reversal,allocation,"PLAN_BALANCE_CHANGED","La cuota o el plan no conserva el importe imputado");
            List<PaymentPlanDebt> links=planDebts.findByPaymentPlanId(plan.id);
            Optional<List<PaymentPlanPrincipalLedger.Share>> recorded;
            try{recorded=principalLedger.find(allocation);}
            catch(BusinessException error){rejectionAudit.record(reversal.id,allocation.id,error.code);throw error;}
            if(recorded.isEmpty()&&allocation.principalApplied.signum()>0&&links.size()!=1)block(reversal,allocation,"HISTORICAL_PLAN_DISTRIBUTION_UNVERIFIABLE","Falta la distribución exacta de capital por deuda para esta cuota histórica");
            if(links.isEmpty())block(reversal,allocation,"PLAN_DISTRIBUTION_INVALID","El plan no conserva sus deudas vinculadas");
            if(recorded.isPresent()&&recorded.orElseThrow().stream().anyMatch(share->links.stream().noneMatch(link->link.debtId.equals(share.debtId()))))block(reversal,allocation,"PLAN_DISTRIBUTION_INVALID","La distribución registrada no corresponde a las deudas del plan");
            if(recorded.isPresent()&&recorded.orElseThrow().stream().anyMatch(share->links.stream().filter(link->link.debtId.equals(share.debtId())).anyMatch(link->link.principalPaidAmount.compareTo(share.amount())<0)))block(reversal,allocation,"PLAN_DISTRIBUTION_INVALID","La deuda no conserva el capital distribuido a la cuota");
            if(recorded.isEmpty()&&links.size()==1&&links.get(0).principalPaidAmount.compareTo(allocation.principalApplied)<0)block(reversal,allocation,"PLAN_DISTRIBUTION_INVALID","La única deuda del plan no conserva el capital imputado");
        }
    }
    private void block(PaymentReversalRequest reversal,PaymentAllocation allocation,String code,String message){
        rejectionAudit.record(reversal.id,allocation.id,code);
        throw new BusinessException(code,message,422);
    }
    private void reverseInstallment(Payment payment,PaymentAllocation a,String reason){
        Long planId=installments.findPaymentPlanIdById(a.installmentId).orElseThrow();PaymentPlan plan=plans.findByIdForUpdate(planId).orElseThrow();Installment i=installments.findByIdForUpdate(a.installmentId).orElseThrow();
        i.paidAmount=i.paidAmount.subtract(a.amount);i.outstandingAmount=i.totalAmount.subtract(i.paidAmount);i.status=i.paidAmount.signum()==0?InstallmentStatus.PENDING:InstallmentStatus.PARTIALLY_PAID;i.paidAt=null;a.reversedAt=OffsetDateTime.now();
        List<PaymentPlanDebt> links=planDebts.findByPaymentPlanId(plan.id);
        Optional<List<PaymentPlanPrincipalLedger.Share>> recorded=principalLedger.find(a);
        if(recorded.isPresent()){
            for(var share:recorded.orElseThrow()){
                PaymentPlanDebt link=links.stream().filter(x->x.debtId.equals(share.debtId())).findFirst().orElseThrow();
                CatalogService.require(link.principalPaidAmount.compareTo(share.amount())>=0,"PLAN_DISTRIBUTION_INVALID","La cuota no coincide con el capital reconocido");
                link.principalPaidAmount=link.principalPaidAmount.subtract(share.amount());link.remainingPrincipalAmount=link.remainingPrincipalAmount.add(share.amount());link.status=PaymentPlanDebtStatus.ACTIVE;
                Debt debt=debts.findByIdForUpdate(link.debtId).orElseThrow();debt.revertirImputacion(share.amount());debt.updatedAt=a.reversedAt;
                events.paymentReversed(payment,a,debt,reason,share.amount());
            }
        }else{
            reverseLegacyPrincipal(a,links);
            if(links.stream().map(link->debts.findById(link.debtId).orElseThrow()).anyMatch(debt->events.externalOrigin(debt).isPresent()))events.deferHistoricalPlanReversal(a);
        }
        plan.paidAmount=plan.paidAmount.subtract(a.amount);plan.outstandingPlanAmount=plan.totalPlanAmount.subtract(plan.paidAmount);
        if(plan.status==PaymentPlanStatus.COMPLETED){plan.status=PaymentPlanStatus.ACTIVE;plan.completedAt=null;}
    }
    private void reverseLegacyPrincipal(PaymentAllocation a,List<PaymentPlanDebt> links){
        BigDecimal restore=a.principalApplied;if(restore.signum()==0)return;
        CatalogService.require(links.size()==1,"HISTORICAL_PLAN_DISTRIBUTION_UNVERIFIABLE","Falta la distribución exacta de capital por deuda para esta cuota histórica");
        PaymentPlanDebt link=links.get(0);CatalogService.require(link.principalPaidAmount.compareTo(restore)>=0,"PLAN_DISTRIBUTION_INVALID","La única deuda del plan no conserva el capital imputado");
        link.principalPaidAmount=link.principalPaidAmount.subtract(restore);link.remainingPrincipalAmount=link.remainingPrincipalAmount.add(restore);link.status=PaymentPlanDebtStatus.ACTIVE;Debt debt=debts.findByIdForUpdate(link.debtId).orElseThrow();debt.revertirImputacion(restore);debt.updatedAt=a.reversedAt;
    }
    private Optional<CreditBalance> validateGeneratedCreditIsUnused(Long paymentId){Optional<CreditBalance> credit=credits.findBySourcePaymentIdForUpdate(paymentId);credit.ifPresent(c->CatalogService.require(c.availableAmount.compareTo(c.originalAmount)==0,"CREDIT_BALANCE_ALREADY_USED","El saldo a favor del pago ya fue utilizado"));return credit;}
    private PaymentReversalRequest get(Long id,String ifMatch){PaymentReversalRequest reversal=reversals.findByIdForUpdate(id).orElseThrow(()->CatalogService.notFound("Reversión"));EntityTags.requireIfMatch(EntityTags.paymentReversal(ApiResponses.of(reversal)),ifMatch);return reversal;}
}
