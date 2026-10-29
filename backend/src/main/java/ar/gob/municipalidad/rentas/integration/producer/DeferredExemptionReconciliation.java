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
class DeferredExemptionReconciliation {
    private final ExemptionRequestRepository requests;
    private final ExemptionRepository exemptions;
    private final TaxpayerRepository taxpayers;
    private final IntegrationEventLogRepository logs;
    private final OutboxRepository outbox;
    private final ConfirmedIntegrationOutbox producer;
    private final AuditService audit;

    DeferredExemptionReconciliation(ExemptionRequestRepository requests,ExemptionRepository exemptions,
        TaxpayerRepository taxpayers,IntegrationEventLogRepository logs,OutboxRepository outbox,
        ConfirmedIntegrationOutbox producer,AuditService audit){
        this.requests=requests;this.exemptions=exemptions;this.taxpayers=taxpayers;this.logs=logs;
        this.outbox=outbox;this.producer=producer;this.audit=audit;
    }

    @Transactional ReconciliationResult reconcile(Long requestId){
        // El bloqueo del agregado serializa los reintentos, incluso entre instancias del backend.
        ExemptionRequest request=requests.findByIdForUpdate(requestId)
            .orElseThrow(()->CatalogService.notFound("Solicitud de exención"));
        TaxpayerReference taxpayer=taxpayers.findById(request.taxpayerId)
            .orElseThrow(()->CatalogService.notFound("Contribuyente"));
        Optional<IntegrationEventLog> requested=marker("exemptionRequested",requestId);
        Optional<IntegrationEventLog> resolved=marker("updateExemptionStatus",requestId);
        boolean requestPending=requested.filter(x->x.status==IntegrationEventStatus.PENDING).isPresent();
        boolean resolutionPending=resolved.filter(x->x.status==IntegrationEventStatus.PENDING).isPresent();
        if(!requestPending&&!resolutionPending)return result(request,taxpayer,"NOTHING_PENDING");

        touch(requested);touch(resolved);
        if(taxpayer.taxpayerType!=TaxpayerType.CITIZEN||taxpayer.canonicalCitizenId==null){
            ReconciliationResult result=result(request,taxpayer,"CANONICAL_CITIZEN_ID_PENDING");
            audit.record("ExemptionRequest",requestId,"DEFERRED_EXEMPTION_RECONCILIATION_CHECKED",result);
            return result;
        }

        // Nunca preparar una resolución antes de haber preparado su solicitud.
        producer.exemptionRequested(request);
        if(resolutionPending){
            if(request.status==ExemptionRequestStatus.APPROVED){
                Exemption exemption=exemptions.findByRequestId(requestId)
                    .orElseThrow(()->new BusinessException("EXEMPTION_RECORD_MISSING","La aprobación no tiene exención persistida",409));
                producer.exemptionApproved(request,exemption);
            }else if(request.status==ExemptionRequestStatus.REJECTED){
                producer.exemptionRejected(request);
            }else{
                ReconciliationResult result=result(request,taxpayer,"RESOLUTION_NOT_FINAL");
                audit.record("ExemptionRequest",requestId,"DEFERRED_EXEMPTION_RECONCILIATION_CHECKED",result);
                return result;
            }
        }
        boolean stillPending=marker("exemptionRequested",requestId).filter(x->x.status==IntegrationEventStatus.PENDING).isPresent()
            ||marker("updateExemptionStatus",requestId).filter(x->x.status==IntegrationEventStatus.PENDING).isPresent();
        ReconciliationResult result=result(request,taxpayer,stillPending?"REVIEW_REQUIRED":"RECONCILED_LOCALLY");
        audit.record("ExemptionRequest",requestId,stillPending?"DEFERRED_EXEMPTION_RECONCILIATION_CHECKED":"DEFERRED_EXEMPTION_RECONCILED",result);
        return result;
    }

    private void touch(Optional<IntegrationEventLog> marker){
        marker.filter(x->x.status==IntegrationEventStatus.PENDING).ifPresent(x->{
            x.retryCount++;x.lastRetryAt=OffsetDateTime.now();
        });
    }
    private Optional<IntegrationEventLog> marker(String type,Long requestId){
        return logs.findFirstByBusinessKeyOrderByIdDesc("outbound-deferred:"+type+":ExemptionRequest:"+requestId);
    }
    private Optional<OutboxEvent> prepared(String type,Long requestId){
        return outbox.findFirstByEventTypeAndAggregateTypeAndAggregateIdOrderByCreatedAtAsc(type,"ExemptionRequest",requestId.toString());
    }
    private ReconciliationResult result(ExemptionRequest request,TaxpayerReference taxpayer,String outcome){
        Optional<IntegrationEventLog> requested=marker("exemptionRequested",request.id);
        Optional<IntegrationEventLog> resolved=marker("updateExemptionStatus",request.id);
        Optional<OutboxEvent> requestEvent=prepared("exemptionRequested",request.id);
        Optional<OutboxEvent> resolutionEvent=prepared("updateExemptionStatus",request.id);
        return new ReconciliationResult(request.id,request.integrationId,taxpayer.canonicalCitizenId,outcome,
            requested.map(x->x.status.name()).orElse("NOT_DEFERRED"),requested.map(x->x.errorMessage).orElse(null),
            requestEvent.map(x->x.id).orElse(null),requestEvent.map(x->x.status.name()).orElse(null),
            resolved.map(x->x.status.name()).orElse("NOT_DEFERRED"),resolved.map(x->x.errorMessage).orElse(null),
            resolutionEvent.map(x->x.id).orElse(null),resolutionEvent.map(x->x.status.name()).orElse(null),
            "NOT_CONFIRMED_BY_CORE");
    }

    record ReconciliationResult(Long requestId,UUID requestIntegrationId,UUID canonicalCitizenId,String outcome,
        String requestDeferralStatus,String requestDeferralReason,UUID requestOutboxEventId,String requestOutboxStatus,
        String resolutionDeferralStatus,String resolutionDeferralReason,UUID resolutionOutboxEventId,
        String resolutionOutboxStatus,String deliveryStatus) {}
}

@RestController
@RequestMapping("/api/v1/integrations/deferred/exemptions")
class DeferredExemptionReconciliationController {
    private final DeferredExemptionReconciliation reconciliation;
    DeferredExemptionReconciliationController(DeferredExemptionReconciliation reconciliation){this.reconciliation=reconciliation;}

    @PostMapping("/{requestId}/reconcile")
    @PreAuthorize("hasAnyRole('SUPERVISOR','TECHNICAL')")
    DeferredExemptionReconciliation.ReconciliationResult reconcile(@PathVariable Long requestId){
        return reconciliation.reconcile(requestId);
    }
}
