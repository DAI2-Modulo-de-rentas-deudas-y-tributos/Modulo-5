package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
class ConfirmedIntegrationOutbox {
    private final OutboxRepository outbox;private final IntegrationEventLogRepository integrationLogs;private final TaxpayerRepository taxpayers;private final TaxConceptRepository concepts;
    private final PaymentPlanRequestDebtRepository planRequestDebts;private final DebtRepository debts;private final LiquidationRepository liquidations;private final ExternalObligationRepository externalObligations;private final ObjectMapper json;
    ConfirmedIntegrationOutbox(OutboxRepository outbox,IntegrationEventLogRepository integrationLogs,TaxpayerRepository taxpayers,TaxConceptRepository concepts,
        PaymentPlanRequestDebtRepository planRequestDebts,DebtRepository debts,LiquidationRepository liquidations,ExternalObligationRepository externalObligations,ObjectMapper json){this.outbox=outbox;this.integrationLogs=integrationLogs;this.taxpayers=taxpayers;this.concepts=concepts;this.planRequestDebts=planRequestDebts;this.debts=debts;this.liquidations=liquidations;this.externalObligations=externalObligations;this.json=json;}

    void exemptionRequested(ExemptionRequest request){
        TaxpayerReference taxpayer=taxpayer(request.taxpayerId);
        if(!canonicalCitizenAvailable(taxpayer)){defer("exemptionRequested","ExemptionRequest",request.id,"CANONICAL_CITIZEN_ID_PENDING");return;}
        TaxConcept concept=concept(request.taxConceptId);
        var payload=new ConfirmedOutboundEvents.ExemptionRequestedPayload(request.integrationId,taxpayer.canonicalCitizenId,concept.code,request.reason,request.requestedPercentage,request.requestedFrom,request.requestedUntil);
        prepareExemption("exemptionRequested",request.id,request.integrationId,taxpayer.canonicalCitizenId,null,payload);
    }
    void exemptionApproved(ExemptionRequest request,Exemption exemption){
        TaxpayerReference taxpayer=taxpayer(request.taxpayerId);
        if(!canonicalCitizenAvailable(taxpayer)){defer("updateExemptionStatus","ExemptionRequest",request.id,"CANONICAL_CITIZEN_ID_PENDING");return;}
        exemptionRequested(request);
        if(!exemptionRequestPrepared(request,taxpayer)){defer("updateExemptionStatus","ExemptionRequest",request.id,"REQUEST_EVENT_REVIEW_REQUIRED");return;}
        TaxConcept concept=concept(request.taxConceptId);
        var payload=new ConfirmedOutboundEvents.UpdateExemptionStatusPayload(request.integrationId,taxpayer.canonicalCitizenId,"APPROVED",exemption.integrationId,concept.code,exemption.percentage,exemption.validFrom,exemption.validUntil,null);
        prepareExemption("updateExemptionStatus",request.id,request.integrationId,taxpayer.canonicalCitizenId,"APPROVED",payload);
    }
    void exemptionRejected(ExemptionRequest request){
        TaxpayerReference taxpayer=taxpayer(request.taxpayerId);
        if(!canonicalCitizenAvailable(taxpayer)){defer("updateExemptionStatus","ExemptionRequest",request.id,"CANONICAL_CITIZEN_ID_PENDING");return;}
        if(request.resolutionReason==null||request.resolutionReason.isBlank()){defer("updateExemptionStatus","ExemptionRequest",request.id,"REJECTION_REASON_PENDING");return;}
        exemptionRequested(request);
        if(!exemptionRequestPrepared(request,taxpayer)){defer("updateExemptionStatus","ExemptionRequest",request.id,"REQUEST_EVENT_REVIEW_REQUIRED");return;}
        TaxConcept concept=concept(request.taxConceptId);
        var payload=new ConfirmedOutboundEvents.UpdateExemptionStatusPayload(request.integrationId,taxpayer.canonicalCitizenId,"REJECTED",null,concept.code,null,null,null,request.resolutionReason);
        prepareExemption("updateExemptionStatus",request.id,request.integrationId,taxpayer.canonicalCitizenId,"REJECTED",payload);
    }

    void paymentPlanRequested(PaymentPlanRequest request){
        TaxpayerReference taxpayer=taxpayer(request.taxpayerId);
        if(!canonicalCitizenAvailable(taxpayer)){defer("paymentPlanRequested","PaymentPlanRequest",request.id,"CANONICAL_CITIZEN_ID_PENDING");return;}
        var payload=paymentPlanPayload(request,taxpayer);
        if(payload==null){
            defer("paymentPlanRequested","PaymentPlanRequest",request.id,"DEBT_UUID_OR_REQUEST_LINK_REVIEW_REQUIRED");return;
        }
        if(preparePlan("paymentPlanRequested",request,payload))resolveDeferral("paymentPlanRequested","PaymentPlanRequest",request.id);
    }
    void paymentPlanGranted(PaymentPlanRequest request,PaymentPlan plan){
        if(request.status!=PaymentPlanRequestStatus.GRANTED||plan.requestId==null||!request.id.equals(plan.requestId)){
            defer("updatePaymentPlanStatus","PaymentPlanRequest",request.id,"PLAN_GRANT_STATE_REVIEW_REQUIRED");return;
        }
        paymentPlanRequested(request);
        if(!paymentPlanRequestPrepared(request)||!canonicalCitizenAvailable(taxpayer(request.taxpayerId))){defer("updatePaymentPlanStatus","PaymentPlanRequest",request.id,"REQUEST_EVENT_REVIEW_REQUIRED");return;}
        var payload=new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload(request.integrationId,taxpayer(request.taxpayerId).canonicalCitizenId,"GRANTED",plan.integrationId,plan.installmentCount,plan.totalPlanAmount,null);
        if(preparePlan("updatePaymentPlanStatus",request,payload))resolveDeferral("updatePaymentPlanStatus","PaymentPlanRequest",request.id);
    }
    void paymentPlanRejected(PaymentPlanRequest request){
        if(request.status!=PaymentPlanRequestStatus.REJECTED||request.resolutionReason==null||request.resolutionReason.isBlank()){
            defer("updatePaymentPlanStatus","PaymentPlanRequest",request.id,"REJECTION_REASON_OR_STATE_REVIEW_REQUIRED");return;
        }
        paymentPlanRequested(request);
        if(!paymentPlanRequestPrepared(request)||!canonicalCitizenAvailable(taxpayer(request.taxpayerId))){defer("updatePaymentPlanStatus","PaymentPlanRequest",request.id,"REQUEST_EVENT_REVIEW_REQUIRED");return;}
        var payload=new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload(request.integrationId,taxpayer(request.taxpayerId).canonicalCitizenId,"REJECTED",null,null,null,request.resolutionReason);
        if(preparePlan("updatePaymentPlanStatus",request,payload))resolveDeferral("updatePaymentPlanStatus","PaymentPlanRequest",request.id);
    }

    void overdueDebt(Debt debt,LocalDate processingDate){
        if(!debt.isCollectible()||!debt.dueDate.isBefore(processingDate))return;
        Debt lockedDebt=debts.findByIdForUpdate(debt.id).orElseThrow(()->CatalogService.notFound("Deuda"));
        if(!lockedDebt.isCollectible()||!lockedDebt.dueDate.isBefore(processingDate))return;
        String idempotencyKey="debtOverdue:Debt:"+lockedDebt.id;
        if(outbox.existsByIdempotencyKey(idempotencyKey))return;
        TaxpayerReference taxpayer=taxpayer(lockedDebt.taxpayerId);
        if(!canonicalCitizenAvailable(taxpayer)){
            defer("debtOverdue","Debt",lockedDebt.id,taxpayer.taxpayerType==TaxpayerType.CITIZEN?"CANONICAL_CITIZEN_ID_PENDING":"M8_ORGANIZATION_POLICY_PENDING");
            return;
        }
        TaxConcept concept=concept(lockedDebt.taxConceptId);
        var payload=new ConfirmedOutboundEvents.OverdueDebtPayload(lockedDebt.integrationId,taxpayer.canonicalCitizenId,concept.code,lockedDebt.outstandingBalance,lockedDebt.dueDate);
        String aggregateId=String.valueOf(lockedDebt.id);var event=core("debtOverdue",payload,"Debt",aggregateId,lockedDebt.integrationId.toString());
        OutboxEvent entity=new OutboxEvent();entity.id=UUID.fromString(event.eventId());entity.eventType=event.eventType();entity.targetModule="CORE";entity.aggregateType="Debt";entity.aggregateId=aggregateId;entity.idempotencyKey=idempotencyKey;entity.payload=write(event);entity.status=OutboxStatus.PENDING;entity.retryCount=0;entity.createdAt=OffsetDateTime.now();outbox.save(entity);
        resolveDeferral("debtOverdue","Debt",lockedDebt.id);
    }
    void paymentRegistered(Payment payment,PaymentAllocation allocation,Debt debt){paymentRegistered(payment,allocation,debt,allocation.amount);}
    void paymentRegistered(Payment payment,PaymentAllocation allocation,Debt debt,BigDecimal amount){externalOrigin(debt).ifPresent(origin->{
        String key=economicKey("paymentRegistered",allocation.id,debt.id);
        if(outbox.existsByIdempotencyKey(key))return;
        var payload=new ConfirmedOutboundEvents.ExternalPaymentRegisteredPayload(payment.integrationId,debt.integrationId,originType(origin.externalType()),origin.externalReferenceId(),infractionId(origin),amount,payment.paidAt,debt.outstandingBalance);
        Optional<String> correlation=economicCorrelation(origin,debt.id);
        if(correlation.isEmpty()){
            deferEconomic("paymentRegistered","PaymentAllocation",allocation.id,debt,origin,payment.id,allocation.id,key,payload,
                missingCorrelationReason(origin));
            return;
        }
        enqueue("PaymentAllocation",allocation.id.toString(),core("paymentRegistered",payload,"Debt",debt.id.toString(),correlation.get(),economicCausation(origin)),key);
        resolveEconomicDeferral("paymentRegistered","PaymentAllocation",allocation.id,debt.id);
    });}
    void paymentReversed(Payment payment,PaymentAllocation allocation,Debt debt,String reason){paymentReversed(payment,allocation,debt,reason,allocation.amount);}
    void paymentReversed(Payment payment,PaymentAllocation allocation,Debt debt,String reason,BigDecimal amount){externalOrigin(debt).ifPresent(origin->{
        String key=economicKey("paymentReversed",allocation.id,debt.id);
        if(outbox.existsByIdempotencyKey(key))return;
        var payload=new ConfirmedOutboundEvents.PaymentReversedPayload(payment.integrationId,debt.integrationId,originType(origin.externalType()),origin.externalReferenceId(),infractionId(origin),amount,allocation.reversedAt,debt.outstandingBalance,reason);
        Optional<String> correlation=economicCorrelation(origin,debt.id);
        if(correlation.isEmpty()||!outbox.existsByIdempotencyKey(economicKey("paymentRegistered",allocation.id,debt.id))){
            deferEconomic("paymentReversed","PaymentAllocation",allocation.id,debt,origin,payment.id,allocation.id,key,payload,
                correlation.isEmpty()?missingCorrelationReason(origin):"PAYMENT_EVENT_NOT_PREPARED");
            return;
        }
        enqueue("PaymentAllocation",allocation.id.toString(),core("paymentReversed",payload,"Debt",debt.id.toString(),correlation.get(),economicCausation(origin)),key);
        resolveEconomicDeferral("paymentReversed","PaymentAllocation",allocation.id,debt.id);
    });}
    void debtSettled(Debt debt){debtSettled(debt,null);}
    void debtSettled(Debt debt,PaymentAllocation allocation){
        if(debt.originType==DebtOriginType.LIQUIDATION){
            TaxpayerReference taxpayer=taxpayer(debt.taxpayerId);
            if(!canonicalCitizenAvailable(taxpayer)){
                defer("debtSettled","Debt",debt.id,"CANONICAL_CITIZEN_ID_PENDING");
                return;
            }
            var payload=municipalDebtSettledCandidate(debt);
            String key="outbound:debtSettled:Debt:"+debt.id;
            if(outbox.existsByIdempotencyKey(key))return;
            enqueue("Debt",debt.id.toString(),core("debtSettled",payload,"Debt",debt.id.toString(),null,null),key);
            resolveDeferral("debtSettled","Debt",debt.id);
            return;
        }
        var origin=externalOrigin(debt);
        if(origin.isEmpty()){
            defer("debtSettled","Debt",debt.id,"EXTERNAL_ORIGIN_CONTRACT_PENDING");
            return;
        }
        TaxpayerReference taxpayer=taxpayer(debt.taxpayerId);TaxConcept concept=concept(debt.taxConceptId);OffsetDateTime settledAt=OffsetDateTime.now();
        String originType=originType(origin.orElseThrow().externalType());
        String originId=origin.orElseThrow().externalReferenceId();
        var payload=new ConfirmedOutboundEvents.DebtSettledPayload(debt.integrationId,canonicalCitizenAvailable(taxpayer)?taxpayer.canonicalCitizenId:null,concept.code,originType,originId,origin.flatMap(x->Optional.ofNullable(infractionId(x))).orElse(null),settledAt,BigDecimal.ZERO.setScale(2),BigDecimal.ZERO.setScale(2));
        boolean m4OrganizationWithoutCitizen="M4".equals(origin.orElseThrow().targetModule())
            &&taxpayer.taxpayerType==TaxpayerType.ORGANIZATION&&taxpayer.canonicalCitizenId==null;
        Object settlementData=payload;
        if(m4OrganizationWithoutCitizen){
            ObjectNode data=json.valueToTree(payload);
            data.putNull("citizenId");
            settlementData=data;
        }
        String key=allocation==null?"outbound:debtSettled:Debt:"+debt.id:economicKey("debtSettled",allocation.id,debt.id);
        if(outbox.existsByIdempotencyKey(key))return;
        Optional<String> correlation=economicCorrelation(origin.orElseThrow(),debt.id);
        if(correlation.isEmpty()||allocation!=null
            &&!outbox.existsByIdempotencyKey(
            economicKey("paymentRegistered",allocation.id,debt.id))){
            deferEconomic("debtSettled","Debt",debt.id,debt,origin.orElseThrow(),allocation==null?null:allocation.paymentId,
                allocation==null?null:allocation.id,key,settlementData,
                correlation.isEmpty()?missingCorrelationReason(origin.orElseThrow()):"PAYMENT_EVENT_NOT_PREPARED");
            return;
        }
        if("M4".equals(origin.orElseThrow().targetModule())&&!canonicalCitizenAvailable(taxpayer)
            &&!m4OrganizationWithoutCitizen){
            deferEconomic("debtSettled","Debt",debt.id,debt,origin.orElseThrow(),allocation==null?null:allocation.paymentId,
                allocation==null?null:allocation.id,key,settlementData,"CANONICAL_CITIZEN_ID_PENDING");
            return;
        }
        enqueue("Debt",debt.id.toString(),core("debtSettled",settlementData,"Debt",debt.id.toString(),correlation.get(),economicCausation(origin.orElseThrow())),key);
        resolveEconomicDeferral("debtSettled","Debt",debt.id,debt.id);
    }

    ConfirmedOutboundEvents.DebtSettledPayload municipalDebtSettledCandidate(Debt debt){
        CatalogService.require(debt.originType==DebtOriginType.LIQUIDATION&&debt.liquidationId!=null,"INVALID_DEBT_ORIGIN","La deuda no proviene de una liquidación municipal");
        CatalogService.require(debt.status==DebtStatus.PAID&&debt.outstandingBalance.signum()==0,"DEBT_NOT_SETTLED","La deuda aún no está saldada");
        TaxpayerReference taxpayer=taxpayer(debt.taxpayerId);
        CatalogService.require(canonicalCitizenAvailable(taxpayer),"CANONICAL_CITIZEN_ID_PENDING","Falta vincular el ciudadano con M1");
        Liquidation liquidation=liquidations.findById(debt.liquidationId).orElseThrow(()->CatalogService.notFound("Liquidación"));
        CatalogService.require(debt.integrationId!=null&&liquidation.integrationId!=null,"INTEGRATION_ID_REQUIRED","Falta el UUID estable de la deuda o liquidación");
        return new ConfirmedOutboundEvents.DebtSettledPayload(debt.integrationId,taxpayer.canonicalCitizenId,
            concept(debt.taxConceptId).code,"MUNICIPAL_TAX",liquidation.integrationId.toString(),null,debt.updatedAt,
            BigDecimal.ZERO.setScale(2),BigDecimal.ZERO.setScale(2));
    }

    Optional<ExternalDebtOrigin> externalOrigin(Debt debt){if(debt.originType!=DebtOriginType.EXTERNAL_OBLIGATION||debt.externalObligationId==null)return Optional.empty();ExternalObligation external=externalObligations.findById(debt.externalObligationId).orElseThrow(()->CatalogService.notFound("Obligación externa"));String source=external.sourceModule.trim().toLowerCase(Locale.ROOT);String target=switch(source){case "m4","habilitaciones"->"M4";case "m7","transito"->"M7";default->null;};return target==null?Optional.empty():Optional.of(new ExternalDebtOrigin(target,external.externalReferenceId,external.externalType,external.sourceEventId));}
    record ExternalDebtOrigin(String targetModule,String externalReferenceId,ExternalObligationType externalType,UUID sourceEventId) {}
    private String economicKey(String type,Long allocationId,Long debtId){return "outbound:"+type+":PaymentAllocation:"+allocationId+":Debt:"+debtId;}
    private String economicDeferralKey(String type,String aggregate,Long aggregateId,Long debtId){return deferralKey(type,aggregate,aggregateId)+":Debt:"+debtId;}
    private void deferEconomic(String type,String aggregate,Long aggregateId,Debt debt,ExternalDebtOrigin origin,
        Long paymentId,Long allocationId,String idempotencyKey,Object payload,String reason){
        String key=economicDeferralKey(type,aggregate,aggregateId,debt.id);
        EconomicMovementSnapshot snapshot=new EconomicMovementSnapshot(type,aggregate,aggregateId,debt.id,paymentId,allocationId,
            origin.sourceEventId(),origin.externalReferenceId(),origin.targetModule(),idempotencyKey,UUID.randomUUID(),
            OffsetDateTime.now(),json.valueToTree(payload));
        Optional<IntegrationEventLog> existing=integrationLogs.findFirstByBusinessKeyOrderByIdDesc(key);
        if(existing.isPresent()){
            IntegrationEventLog log=existing.orElseThrow();
            if(log.status!=IntegrationEventStatus.PENDING)return;
            try{
                EconomicMovementSnapshot prior=json.treeToValue(json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(log.payload).path("snapshot"),EconomicMovementSnapshot.class);
                if(prior==null||!prior.sameMovement(snapshot)){
                    log.errorMessage="ECONOMIC_SNAPSHOT_REVIEW_REQUIRED";
                    return;
                }
                log.errorMessage=reason;
                return;
            }catch(Exception ex){log.errorMessage="HISTORICAL_MOVEMENT_SNAPSHOT_UNAVAILABLE";return;}
        }
        OffsetDateTime now=OffsetDateTime.now();IntegrationEventLog log=new IntegrationEventLog();log.eventId=UUID.randomUUID();log.externalEventId=log.eventId.toString();
        log.businessKey=key;log.eventType=type;log.sourceModule="rentas";log.targetModule="CORE";log.direction=EventDirection.OUTBOUND;
        log.status=IntegrationEventStatus.PENDING;log.payload=write(Map.of("phase","DEFERRED","reason",reason,"snapshot",snapshot));
        log.retryCount=0;log.errorMessage=reason;log.occurredAt=now;log.receivedAt=now;integrationLogs.save(log);
    }
    OutboxEvent prepareDeferredEconomic(EconomicMovementSnapshot snapshot,String correlationId){
        var event=new ConfirmedOutboundEvents.CoreEnvelope<>(snapshot.eventId().toString(),snapshot.eventType(),"1.0",
            snapshot.occurredAt(),"rentas",correlationId,null,snapshot.data());
        OutboxEvent entity=new OutboxEvent();entity.id=snapshot.eventId();entity.eventType=snapshot.eventType();entity.targetModule="CORE";
        entity.aggregateType=snapshot.aggregateType();entity.aggregateId=snapshot.aggregateId().toString();
        entity.idempotencyKey=snapshot.idempotencyKey();entity.payload=write(event);entity.status=OutboxStatus.PENDING;
        entity.retryCount=0;entity.createdAt=OffsetDateTime.now();return outbox.save(entity);
    }
    private void resolveEconomicDeferral(String type,String aggregate,Long aggregateId,Long debtId){
        integrationLogs.findFirstByBusinessKeyOrderByIdDesc(economicDeferralKey(type,aggregate,aggregateId,debtId))
            .filter(log->log.status==IntegrationEventStatus.PENDING).ifPresent(log->{
                try{
                    JsonNode snapshot=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(log.payload).path("snapshot");
                    if(snapshot.isMissingNode())return;
                    String key=snapshot.path("idempotencyKey").asText(null);
                    OutboxEvent prepared=key==null?null:outbox.findByIdempotencyKey(key).orElse(null);
                    if(prepared==null)return;
                    log.status=IntegrationEventStatus.PROCESSED;log.processedAt=OffsetDateTime.now();log.errorMessage=null;
                    log.payload=write(Map.of("phase","OUTBOX_PREPARED","snapshot",snapshot,"outboxEventId",prepared.id.toString(),"deliveryConfirmed",false));
                }catch(Exception ex){log.errorMessage="ECONOMIC_SNAPSHOT_REVIEW_REQUIRED";}
            });
    }
    Optional<String> economicCorrelation(ExternalDebtOrigin origin,Long debtId){
        if("M4".equals(origin.targetModule())){
            if(!canonicalUuid(origin.externalReferenceId()))return Optional.empty();
            return integrationLogs.findFirstByEventIdOrderByIdDesc(origin.sourceEventId())
            .filter(log->log.direction==EventDirection.INBOUND&&Set.of("permitFeeGenerated","commercialFineGenerated").contains(log.eventType))
            .flatMap(log->{
                try{
                    JsonNode received=json.readTree(log.payload);
                    if(!"habilitaciones".equals(received.path("sourceModule").asText())
                        ||!origin.sourceEventId().toString().equals(received.path("eventId").asText())
                        ||!"1.0".equals(received.path("eventVersion").asText())
                        ||!(origin.externalType()==ExternalObligationType.PERMIT_FEE?"permitFeeGenerated":"commercialFineGenerated")
                            .equals(log.eventType)
                        ||!origin.externalReferenceId().equals(received.path("data").path("id").asText()))return Optional.empty();
                    String value=received.path("correlationId").asText(null);
                    return value==null?Optional.empty():Optional.of(UUID.fromString(value).toString());
                }catch(Exception ex){return Optional.empty();}
            });
        }
        if(!canonicalUuid(origin.externalReferenceId()))return Optional.empty();
        return integrationLogs.findFirstByEventIdOrderByIdDesc(origin.sourceEventId())
            .filter(log->log.direction==EventDirection.INBOUND&&"infractionConfirmed".equals(log.eventType))
            .flatMap(log->{
                try{
                    JsonNode received=json.readTree(log.payload);
                    if(!"transito".equals(received.path("sourceModule").asText())
                        ||!origin.sourceEventId().toString().equals(received.path("eventId").asText())
                        ||!"infractionConfirmed".equals(received.path("eventType").asText())
                        ||!"1.0".equals(received.path("eventVersion").asText())
                        ||!origin.externalReferenceId().equals(received.path("data").path("infractionId").asText()))return Optional.empty();
                    String value=received.path("correlationId").asText(null);
                    return value==null?Optional.empty():Optional.of(UUID.fromString(value).toString());
                }catch(Exception ex){return Optional.empty();}
            });
    }
    private String missingCorrelationReason(ExternalDebtOrigin origin){
        if("M4".equals(origin.targetModule())&&!canonicalUuid(origin.externalReferenceId()))
            return "M4_ORIGINAL_ORIGIN_ID_INVALID";
        if("M7".equals(origin.targetModule())&&!canonicalUuid(origin.externalReferenceId()))
            return "M7_ORIGINAL_INFRACTION_ID_INVALID";
        return "M7".equals(origin.targetModule())?"M7_ORIGINAL_CORRELATION_ID_UNAVAILABLE":"M4_ORIGINAL_CORRELATION_ID_UNAVAILABLE";
    }
    private boolean canonicalUuid(String value){
        try{return UUID.fromString(value).toString().equals(value);}catch(Exception ex){return false;}
    }
    private String economicCausation(ExternalDebtOrigin origin){
        return null;
    }
    void deferHistoricalPlanReversal(PaymentAllocation allocation){defer("paymentReversed","PaymentAllocation",allocation.id,"LEGACY_PLAN_DISTRIBUTION_UNAVAILABLE");}

    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.M2UpdateTicketStatusData> ticketStatus(String ticketId,String updateType,String publicMessage,String internalMessage,JsonNode details,UUID actorId,String correlationId,String causationId){
        CatalogService.require(actorId!=null,"INTEGRATION_ACTOR_ID_REQUIRED","El empleado autenticado no tiene UUID de integración; no se puede informar el cambio a M2");
        return ticketStatus(ticketId,updateType,publicMessage,internalMessage,details,new ConfirmedOutboundEvents.UpdatedBy("EXTERNAL_USER",actorId.toString()),correlationId,causationId);
    }
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.M2UpdateTicketStatusData> systemTicketStatus(String ticketId,String updateType,String publicMessage,String internalMessage,JsonNode details,String correlationId,String causationId){
        return ticketStatus(ticketId,updateType,publicMessage,internalMessage,details,new ConfirmedOutboundEvents.UpdatedBy("SYSTEM",null),correlationId,causationId);
    }
    private ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.M2UpdateTicketStatusData> ticketStatus(String ticketId,String updateType,String publicMessage,String internalMessage,JsonNode details,ConfirmedOutboundEvents.UpdatedBy actor,String correlationId,String causationId){
        CatalogService.require(isUuid(ticketId),"INVALID_TICKET_ID","ticketId de M2 debe ser UUID");
        CatalogService.require(correlationId!=null&&isUuid(correlationId),"M2_CORRELATION_REQUIRED","updateTicketStatus requiere el correlationId original de M2");
        CatalogService.require(causationId==null||isUuid(causationId),"INVALID_CAUSATION_ID","causationId debe ser UUID cuando exista");
        validateTicketStatus(updateType,publicMessage,internalMessage,details);
        OffsetDateTime at=OffsetDateTime.now();var data=new ConfirmedOutboundEvents.M2UpdateTicketStatusData(ticketId,updateType,publicMessage,internalMessage,null,details,List.of(),actor,at);
        return core("updateTicketStatus",data,"TicketCase",ticketId,correlationId,causationId);
    }
    void ticketStatus(String ticketId,String aggregateId,String updateType,String publicMessage,String internalMessage,JsonNode details,UUID actorId,String correlationId,String causationId){
        var event=ticketStatus(ticketId,updateType,publicMessage,internalMessage,details,actorId,correlationId,causationId);
        enqueue("CORE","TicketCase",aggregateId,event);
    }
    private void validateTicketStatus(String type,String publicMessage,String internalMessage,JsonNode details){
        CatalogService.require(allowed(type,"STARTED","PROGRESS","INFORMATION_REQUIRED","RETURNED","RESOLVED","REJECTED"),"UNSUPPORTED_UPDATE_TYPE","updateType de M2 no soportado");
        boolean hasMessage=nonBlank(publicMessage)||nonBlank(internalMessage);
        switch(type){
            case "PROGRESS"->CatalogService.require(hasMessage,"INVALID_EVENT","PROGRESS requiere un mensaje o avance útil");
            case "INFORMATION_REQUIRED"->CatalogService.require(nonBlank(detail(details,"informationRequest","messageForCitizen")),"INVALID_EVENT","INFORMATION_REQUIRED requiere messageForCitizen");
            case "RETURNED"->{
                CatalogService.require(allowed(detail(details,"returnInfo","reasonCode"),"INVALID_INFORMATION","REQUEST_TYPE_MISMATCH","INSUFFICIENT_CONTEXT","OTHER"),"INVALID_EVENT","RETURNED requiere un reasonCode válido");
                CatalogService.require(hasMessage,"INVALID_EVENT","RETURNED requiere una explicación");
            }
            case "RESOLVED"->{
                CatalogService.require(allowed(detail(details,"resolution","type"),"ACTION_COMPLETED","REQUEST_FULFILLED","INQUIRY_ANSWERED","ACKNOWLEDGED","NO_FURTHER_ACTION_REQUIRED"),"INVALID_EVENT","RESOLVED requiere resolution.type válido");
                CatalogService.require(nonBlank(publicMessage),"INVALID_EVENT","RESOLVED requiere publicMessage");
            }
            case "REJECTED"->{
                CatalogService.require(allowed(detail(details,"cancellation","reasonCode"),"OUT_OF_SCOPE","DOES_NOT_APPLY","INVALID_REQUEST_TYPE","INVALID_DATA","REJECTED_BY_AREA","OTHER"),"INVALID_EVENT","REJECTED requiere cancellation.reasonCode válido");
                CatalogService.require(hasMessage,"INVALID_EVENT","REJECTED requiere una explicación");
            }
            default->{}
        }
    }
    private boolean nonBlank(String value){return value!=null&&!value.isBlank();}
    private boolean allowed(String value,String... allowed){return value!=null&&Arrays.asList(allowed).contains(value);}
    private String detail(JsonNode details,String branch,String field){return details==null?null:details.path(branch).path(field).asText(null);}

    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.UpdateExemptionStatusPayload> exemptionStatusEvent(ConfirmedOutboundEvents.UpdateExemptionStatusPayload payload){return core("updateExemptionStatus",payload,"ExemptionRequest",payload.requestId().toString());}
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.ExemptionRequestedPayload> exemptionRequestedEvent(ConfirmedOutboundEvents.ExemptionRequestedPayload payload){return core("exemptionRequested",payload,"ExemptionRequest",payload.requestId().toString());}
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.PaymentPlanRequestedPayload> paymentPlanRequestedEvent(ConfirmedOutboundEvents.PaymentPlanRequestedPayload payload){return core("paymentPlanRequested",payload,"PaymentPlanRequest",payload.requestId().toString());}
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload> paymentPlanStatusEvent(ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload payload){return core("updatePaymentPlanStatus",payload,"PaymentPlanRequest",payload.requestId().toString());}
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.OverdueDebtPayload> overdueDebtEvent(ConfirmedOutboundEvents.OverdueDebtPayload payload){return core("debtOverdue",payload,"Debt",payload.debtId().toString(),payload.debtId().toString());}
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.DebtSettledPayload> debtSettledEvent(ConfirmedOutboundEvents.DebtSettledPayload payload){return core("debtSettled",payload,"Debt",payload.debtId().toString());}

    private <T> void enqueueSimple(String type,T payload,String aggregate,Object aggregateId){var event=core(type,payload,aggregate,String.valueOf(aggregateId));enqueue(aggregate,String.valueOf(aggregateId),event);}
    private <T> ConfirmedOutboundEvents.CoreEnvelope<T> core(String type,T data,String aggregate,String aggregateId){return new ConfirmedOutboundEvents.CoreEnvelope<>(UUID.randomUUID().toString(),type,"1.0",OffsetDateTime.now(),"rentas",UUID.randomUUID().toString(),null,data);}
    private <T> ConfirmedOutboundEvents.CoreEnvelope<T> core(String type,T data,String aggregate,String aggregateId,String correlationId){return new ConfirmedOutboundEvents.CoreEnvelope<>(UUID.randomUUID().toString(),type,"1.0",OffsetDateTime.now(),"rentas",correlationId,null,data);}
    private <T> ConfirmedOutboundEvents.CoreEnvelope<T> core(String type,T data,String aggregate,String aggregateId,String correlationId,String causationId){return new ConfirmedOutboundEvents.CoreEnvelope<>(UUID.randomUUID().toString(),type,"1.0",OffsetDateTime.now(),"rentas",correlationId,causationId,data);}
    private void enqueue(String aggregate,String aggregateId,ConfirmedOutboundEvents.CoreEnvelope<?> event){enqueue("CORE",aggregate,aggregateId,event);}
    private void enqueue(String target,String aggregate,String aggregateId,ConfirmedOutboundEvents.CoreEnvelope<?> event){enqueue(target,aggregate,aggregateId,event,null);}
    private void enqueue(String aggregate,String aggregateId,ConfirmedOutboundEvents.CoreEnvelope<?> event,String idempotencyKey){enqueue("CORE",aggregate,aggregateId,event,idempotencyKey);}
    private void enqueue(String target,String aggregate,String aggregateId,ConfirmedOutboundEvents.CoreEnvelope<?> event,String idempotencyKey){OutboxEvent entity=new OutboxEvent();entity.id=UUID.fromString(event.eventId());entity.eventType=event.eventType();entity.targetModule=target;entity.aggregateType=aggregate;entity.aggregateId=aggregateId;entity.idempotencyKey=idempotencyKey;entity.payload=write(event);entity.status=OutboxStatus.PENDING;entity.retryCount=0;entity.createdAt=OffsetDateTime.now();outbox.save(entity);}
    private boolean isUuid(String value){try{UUID.fromString(value);return true;}catch(IllegalArgumentException ex){return false;}}
    private String originType(ExternalObligationType type){return type==ExternalObligationType.TRAFFIC_INFRACTION?"TRAFFIC_FINE":type.name();}
    private String infractionId(ExternalDebtOrigin origin){return origin.externalType()==ExternalObligationType.TRAFFIC_INFRACTION?origin.externalReferenceId():null;}
    private boolean canonicalCitizenAvailable(TaxpayerReference taxpayer){return taxpayer.taxpayerType==TaxpayerType.CITIZEN&&taxpayer.canonicalCitizenId!=null;}
    private String deferralKey(String type,String aggregate,Long aggregateId){return "outbound-deferred:"+type+":"+aggregate+":"+aggregateId;}
    private Optional<OutboxEvent> exemptionOutbox(String type,Long requestId){
        return outbox.findFirstByEventTypeAndAggregateTypeAndAggregateIdOrderByCreatedAtAsc(type,"ExemptionRequest",requestId.toString());
    }
    private boolean exemptionRequestPrepared(ExemptionRequest request,TaxpayerReference taxpayer){
        TaxConcept concept=concept(request.taxConceptId);
        var expected=new ConfirmedOutboundEvents.ExemptionRequestedPayload(request.integrationId,taxpayer.canonicalCitizenId,
            concept.code,request.reason,request.requestedPercentage,request.requestedFrom,request.requestedUntil);
        return exemptionOutbox("exemptionRequested",request.id)
            .filter(event->matchesExemptionOutbox(event,request.integrationId,taxpayer.canonicalCitizenId,null)
                &&matchesExemptionPayload(event,expected)&&requestThread(event).isPresent()).isPresent();
    }
    private boolean matchesExemptionOutbox(OutboxEvent event,UUID requestId,UUID citizenId,String status){
        try{
            JsonNode data=json.readTree(event.payload).path("data");
            return requestId.toString().equals(data.path("requestId").asText())
                &&citizenId.toString().equals(data.path("citizenId").asText())
                &&(status==null||status.equals(data.path("status").asText()));
        }catch(Exception ex){return false;}
    }
    private boolean matchesExemptionPayload(OutboxEvent event,Object expected){
        try{return json.valueToTree(expected).equals((left,right)->
            left.isNumber()&&right.isNumber()?left.decimalValue().compareTo(right.decimalValue())
                :left.equals(right)?0:1,json.readTree(event.payload).path("data"));}
        catch(Exception ex){return false;}
    }
    private boolean prepareExemption(String type,Long requestId,UUID integrationId,UUID citizenId,String status,Object payload){
        var existing=exemptionOutbox(type,requestId);
        if(existing.isPresent()){
            if(!matchesExemptionOutbox(existing.orElseThrow(),integrationId,citizenId,status)
                ||!matchesExemptionPayload(existing.orElseThrow(),payload)
                ||!matchesM1Thread(existing.orElseThrow(),"exemptionRequested".equals(type)
                    ?Optional.empty():exemptionOutbox("exemptionRequested",requestId))){
                defer(type,"ExemptionRequest",requestId,"EXISTING_OUTBOX_CONTRACT_REVIEW_REQUIRED");
                return false;
            }
            resolveDeferral(type,"ExemptionRequest",requestId);
            return true;
        }
        ConfirmedOutboundEvents.CoreEnvelope<?> event;
        if("exemptionRequested".equals(type))event=core(type,payload,"ExemptionRequest",requestId.toString(),integrationId.toString());
        else{
            var source=exemptionOutbox("exemptionRequested",requestId);
            var thread=source.flatMap(this::requestThread);
            if(thread.isEmpty()){
                defer(type,"ExemptionRequest",requestId,"REQUEST_CORRELATION_REVIEW_REQUIRED");
                return false;
            }
            event=core(type,payload,"ExemptionRequest",requestId.toString(),thread.orElseThrow(),source.orElseThrow().id.toString());
        }
        OutboxEvent entity=new OutboxEvent();entity.id=UUID.fromString(event.eventId());entity.eventType=type;
        entity.targetModule="CORE";entity.aggregateType="ExemptionRequest";entity.aggregateId=requestId.toString();
        entity.idempotencyKey="outbound:"+type+":ExemptionRequest:"+requestId;
        entity.payload=write(event);entity.status=OutboxStatus.PENDING;entity.retryCount=0;entity.createdAt=OffsetDateTime.now();
        outbox.save(entity);
        resolveDeferral(type,"ExemptionRequest",requestId);
        return true;
    }
    private boolean paymentPlanRequestPrepared(PaymentPlanRequest request){
        TaxpayerReference taxpayer=taxpayer(request.taxpayerId);
        ConfirmedOutboundEvents.PaymentPlanRequestedPayload payload=paymentPlanPayload(request,taxpayer);
        if(payload==null)return false;
        try{
            var expected=json.readTree(write(payload));
            return planOutbox("paymentPlanRequested",request.id)
                .filter(event->matchesPlanPayload(event,expected)&&requestThread(event).isPresent()).isPresent();
        }catch(Exception ex){return false;}
    }
    private ConfirmedOutboundEvents.PaymentPlanRequestedPayload paymentPlanPayload(PaymentPlanRequest request,TaxpayerReference taxpayer){
        if(!canonicalCitizenAvailable(taxpayer))return null;
        List<PaymentPlanRequestDebt> links=planRequestDebts.findByRequestIdOrderByIdAsc(request.id);
        List<Debt> linkedDebts=debts.findAllById(links.stream().map(x->x.debtId).toList());
        Map<Long,Debt> byId=new HashMap<>();linkedDebts.forEach(x->byId.put(x.id,x));
        List<UUID> debtIds=links.stream().map(x->byId.get(x.debtId)).filter(Objects::nonNull).map(x->x.integrationId).toList();
        if(links.isEmpty()||debtIds.size()!=links.size()||debtIds.stream().anyMatch(Objects::isNull))return null;
        try{return new ConfirmedOutboundEvents.PaymentPlanRequestedPayload(request.integrationId,taxpayer.canonicalCitizenId,
            debtIds,request.totalDebtAtRequest,request.requestedInstallments);}catch(IllegalArgumentException ex){return null;}
    }
    private Optional<OutboxEvent> planOutbox(String type,Long requestId){
        return outbox.findFirstByEventTypeAndAggregateTypeAndAggregateIdOrderByCreatedAtAsc(type,"PaymentPlanRequest",requestId.toString());
    }
    private boolean preparePlan(String type,PaymentPlanRequest request,Object payload){
        String key="outbound:"+type+":PaymentPlanRequest:"+request.integrationId;
        JsonNode expected;
        try{expected=json.readTree(write(payload));}catch(Exception ex){defer(type,"PaymentPlanRequest",request.id,"OUTBOX_PAYLOAD_REVIEW_REQUIRED");return false;}
        Optional<OutboxEvent> existing=outbox.findByIdempotencyKey(key).or(()->planOutbox(type,request.id));
        if(existing.isPresent()){
            try{
                if(!expected.equals(json.readTree(existing.orElseThrow().payload).path("data"))
                    ||!matchesM1Thread(existing.orElseThrow(),"paymentPlanRequested".equals(type)
                        ?Optional.empty():planOutbox("paymentPlanRequested",request.id))){
                    defer(type,"PaymentPlanRequest",request.id,"EXISTING_OUTBOX_CONTRACT_REVIEW_REQUIRED");return false;
                }
                existing.orElseThrow().idempotencyKey=key;
                resolveDeferral(type,"PaymentPlanRequest",request.id);
                return true;
            }catch(Exception ex){
                defer(type,"PaymentPlanRequest",request.id,"EXISTING_OUTBOX_CONTRACT_REVIEW_REQUIRED");return false;
            }
        }
        ConfirmedOutboundEvents.CoreEnvelope<?> event;
        if("paymentPlanRequested".equals(type))event=core(type,payload,"PaymentPlanRequest",request.id.toString(),request.integrationId.toString());
        else{
            var source=planOutbox("paymentPlanRequested",request.id);
            var thread=source.flatMap(this::requestThread);
            if(thread.isEmpty()){
                defer(type,"PaymentPlanRequest",request.id,"REQUEST_CORRELATION_REVIEW_REQUIRED");
                return false;
            }
            event=core(type,payload,"PaymentPlanRequest",request.id.toString(),thread.orElseThrow(),source.orElseThrow().id.toString());
        }
        OutboxEvent entity=new OutboxEvent();entity.id=UUID.fromString(event.eventId());entity.eventType=type;entity.targetModule="CORE";
        entity.aggregateType="PaymentPlanRequest";entity.aggregateId=request.id.toString();entity.idempotencyKey=key;
        entity.payload=write(event);entity.status=OutboxStatus.PENDING;entity.retryCount=0;entity.createdAt=OffsetDateTime.now();outbox.save(entity);
        return true;
    }
    private Optional<String> requestThread(OutboxEvent request){
        try{
            JsonNode envelope=json.readTree(request.payload);
            String correlation=envelope.path("correlationId").asText(null);
            return request.id.toString().equals(envelope.path("eventId").asText())
                &&"rentas".equals(envelope.path("sourceModule").asText())
                &&"1.0".equals(envelope.path("eventVersion").asText())
                &&correlation!=null&&isUuid(correlation)
                ?Optional.of(correlation):Optional.empty();
        }catch(Exception ex){return Optional.empty();}
    }
    private boolean matchesM1Thread(OutboxEvent event,Optional<OutboxEvent> request){
        try{
            JsonNode envelope=json.readTree(event.payload);
            if(!event.id.toString().equals(envelope.path("eventId").asText()))return false;
            String correlation=envelope.path("correlationId").asText(null);
            if(request.isEmpty())return requestThread(event).isPresent()
                &&envelope.path("causationId").isNull();
            String sourceThread=requestThread(request.orElseThrow()).orElse(null);
            return sourceThread!=null&&sourceThread.equals(correlation)
                &&request.orElseThrow().id.toString().equals(envelope.path("causationId").asText());
        }catch(Exception ex){return false;}
    }
    private boolean matchesPlanPayload(OutboxEvent event,JsonNode expected){
        try{return expected.equals(json.readTree(event.payload).path("data"));}
        catch(Exception ex){return false;}
    }
    private void defer(String type,String aggregate,Long aggregateId,String reason){
        String key=deferralKey(type,aggregate,aggregateId);
        var existing=integrationLogs.findFirstByBusinessKeyOrderByIdDesc(key);
        if(existing.isPresent()){
            IntegrationEventLog log=existing.orElseThrow();
            log.status=IntegrationEventStatus.PENDING;log.processedAt=null;log.errorMessage=reason;
            log.payload=write(Map.of("phase","DEFERRED","aggregateType",aggregate,"aggregateId",aggregateId,"reason",reason));
            return;
        }
        OffsetDateTime now=OffsetDateTime.now();IntegrationEventLog log=new IntegrationEventLog();log.eventId=UUID.randomUUID();log.externalEventId=log.eventId.toString();
        log.businessKey=key;log.eventType=type;log.sourceModule="rentas";log.targetModule="CORE";log.direction=EventDirection.OUTBOUND;
        log.status=IntegrationEventStatus.PENDING;log.payload=write(Map.of("phase","DEFERRED","aggregateType",aggregate,"aggregateId",aggregateId,"reason",reason));
        log.retryCount=0;log.errorMessage=reason;log.occurredAt=now;log.receivedAt=now;integrationLogs.save(log);
    }
    private void resolveDeferral(String type,String aggregate,Long aggregateId){
        integrationLogs.findFirstByBusinessKeyOrderByIdDesc(deferralKey(type,aggregate,aggregateId))
            .filter(x->x.status==IntegrationEventStatus.PENDING).ifPresent(x->{
                OutboxEvent event=outbox.findFirstByEventTypeAndAggregateTypeAndAggregateIdOrderByCreatedAtAsc(type,aggregate,aggregateId.toString()).orElse(null);
                x.status=IntegrationEventStatus.PROCESSED;x.processedAt=OffsetDateTime.now();x.errorMessage=null;
                if(event!=null)x.payload=write(Map.of("phase","OUTBOX_PREPARED","aggregateType",aggregate,"aggregateId",aggregateId,
                    "outboxEventId",event.id.toString(),"deliveryConfirmed",false));
            });
    }
    private TaxpayerReference taxpayer(Long id){return taxpayers.findById(id).orElseThrow(()->CatalogService.notFound("Contribuyente"));}
    private TaxConcept concept(Long id){return concepts.findById(id).orElseThrow(()->CatalogService.notFound("Concepto"));}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new BusinessException("INVALID_EVENT_PAYLOAD","No se pudo serializar el evento saliente",422);}}
}
