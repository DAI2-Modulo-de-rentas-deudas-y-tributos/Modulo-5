package ar.gob.municipalidad.rentas;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
class DeferredPaymentPlanReconciliation {
    private final PaymentPlanRequestRepository requests;
    private final PaymentPlanRepository plans;
    private final TaxpayerRepository taxpayers;
    private final IntegrationEventLogRepository logs;
    private final OutboxRepository outbox;
    private final ConfirmedIntegrationOutbox producer;
    private final AuditService audit;

    DeferredPaymentPlanReconciliation(PaymentPlanRequestRepository requests,PaymentPlanRepository plans,
        TaxpayerRepository taxpayers,IntegrationEventLogRepository logs,OutboxRepository outbox,
        ConfirmedIntegrationOutbox producer,AuditService audit){
        this.requests=requests;this.plans=plans;this.taxpayers=taxpayers;this.logs=logs;this.outbox=outbox;
        this.producer=producer;this.audit=audit;
    }

    @Transactional Result reconcile(Long requestId){
        PaymentPlanRequest request=requests.findByIdForUpdate(requestId)
            .orElseThrow(()->CatalogService.notFound("Solicitud de plan"));
        TaxpayerReference taxpayer=taxpayers.findById(request.taxpayerId)
            .orElseThrow(()->CatalogService.notFound("Contribuyente"));
        Optional<IntegrationEventLog> requested=marker("paymentPlanRequested",requestId);
        Optional<IntegrationEventLog> resolved=marker("updatePaymentPlanStatus",requestId);
        boolean requestPending=requested.filter(x->x.status==IntegrationEventStatus.PENDING).isPresent();
        boolean resolutionPending=resolved.filter(x->x.status==IntegrationEventStatus.PENDING).isPresent();
        if(!requestPending&&!resolutionPending)return result(request,taxpayer,"NOTHING_PENDING");
        touch(requested);touch(resolved);
        if(taxpayer.taxpayerType!=TaxpayerType.CITIZEN||taxpayer.canonicalCitizenId==null){
            Result result=result(request,taxpayer,"CANONICAL_CITIZEN_ID_PENDING");
            audit.record("PaymentPlanRequest",request.id,"DEFERRED_PAYMENT_PLAN_RECONCILIATION_CHECKED",result);
            return result;
        }
        producer.paymentPlanRequested(request);
        if(resolutionPending){
            if(request.status==PaymentPlanRequestStatus.GRANTED){
                if(request.paymentPlanId==null){
                    Result result=result(request,taxpayer,"GRANTED_PLAN_ID_MISSING");
                    audit.record("PaymentPlanRequest",request.id,"DEFERRED_PAYMENT_PLAN_RECONCILIATION_CHECKED",result);
                    return result;
                }
                PaymentPlan plan=plans.findById(request.paymentPlanId).orElseThrow(()->new BusinessException("PAYMENT_PLAN_RECORD_MISSING","La aprobación no tiene plan persistido",409));
                producer.paymentPlanGranted(request,plan);
            }else if(request.status==PaymentPlanRequestStatus.REJECTED)producer.paymentPlanRejected(request);
            else{
                Result result=result(request,taxpayer,"RESOLUTION_NOT_FINAL");
                audit.record("PaymentPlanRequest",request.id,"DEFERRED_PAYMENT_PLAN_RECONCILIATION_CHECKED",result);
                return result;
            }
        }
        boolean stillPending=marker("paymentPlanRequested",requestId).filter(x->x.status==IntegrationEventStatus.PENDING).isPresent()
            ||marker("updatePaymentPlanStatus",requestId).filter(x->x.status==IntegrationEventStatus.PENDING).isPresent();
        Result result=result(request,taxpayer,stillPending?"REVIEW_REQUIRED":"RECONCILED_LOCALLY");
        audit.record("PaymentPlanRequest",request.id,stillPending?"DEFERRED_PAYMENT_PLAN_RECONCILIATION_CHECKED":"DEFERRED_PAYMENT_PLAN_RECONCILED",result);
        return result;
    }

    private void touch(Optional<IntegrationEventLog> marker){marker.filter(x->x.status==IntegrationEventStatus.PENDING).ifPresent(x->{x.retryCount++;x.lastRetryAt=OffsetDateTime.now();});}
    private Optional<IntegrationEventLog> marker(String type,Long id){return logs.findFirstByBusinessKeyOrderByIdDesc("outbound-deferred:"+type+":PaymentPlanRequest:"+id);}
    private Optional<OutboxEvent> prepared(String type,Long id){return outbox.findFirstByEventTypeAndAggregateTypeAndAggregateIdOrderByCreatedAtAsc(type,"PaymentPlanRequest",id.toString());}
    private Result result(PaymentPlanRequest request,TaxpayerReference taxpayer,String outcome){
        Optional<IntegrationEventLog> requested=marker("paymentPlanRequested",request.id),resolved=marker("updatePaymentPlanStatus",request.id);
        Optional<OutboxEvent> requestEvent=prepared("paymentPlanRequested",request.id),resolutionEvent=prepared("updatePaymentPlanStatus",request.id);
        return new Result(request.id,request.integrationId,taxpayer.canonicalCitizenId,outcome,
            requested.map(x->x.status.name()).orElse("NOT_DEFERRED"),requested.map(x->x.errorMessage).orElse(null),
            requestEvent.map(x->x.id).orElse(null),requestEvent.map(x->x.status.name()).orElse(null),
            resolved.map(x->x.status.name()).orElse("NOT_DEFERRED"),resolved.map(x->x.errorMessage).orElse(null),
            resolutionEvent.map(x->x.id).orElse(null),resolutionEvent.map(x->x.status.name()).orElse(null),"NOT_CONFIRMED_BY_CORE");
    }
    record Result(Long requestId,UUID requestIntegrationId,UUID canonicalCitizenId,String outcome,
        String requestDeferralStatus,String requestDeferralReason,UUID requestOutboxEventId,String requestOutboxStatus,
        String resolutionDeferralStatus,String resolutionDeferralReason,UUID resolutionOutboxEventId,
        String resolutionOutboxStatus,String deliveryStatus){}
}

@RestController
@RequestMapping("/api/v1/integrations/deferred/payment-plans")
class DeferredPaymentPlanReconciliationController {
    private final DeferredPaymentPlanReconciliation reconciliation;
    DeferredPaymentPlanReconciliationController(DeferredPaymentPlanReconciliation reconciliation){this.reconciliation=reconciliation;}
    @PostMapping("/{requestId}/reconcile")
    @PreAuthorize("hasAnyRole('SUPERVISOR','TECHNICAL')")
    DeferredPaymentPlanReconciliation.Result reconcile(@PathVariable Long requestId){return reconciliation.reconcile(requestId);}
}
