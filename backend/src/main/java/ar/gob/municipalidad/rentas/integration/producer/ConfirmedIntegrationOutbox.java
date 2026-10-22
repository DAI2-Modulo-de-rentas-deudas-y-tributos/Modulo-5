package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
class ConfirmedIntegrationOutbox {
    private final OutboxRepository outbox;private final TaxpayerRepository taxpayers;private final TaxConceptRepository concepts;
    private final PaymentPlanRequestDebtRepository planRequestDebts;private final DebtRepository debts;private final ExternalObligationRepository externalObligations;private final ObjectMapper json;
    ConfirmedIntegrationOutbox(OutboxRepository outbox,TaxpayerRepository taxpayers,TaxConceptRepository concepts,
        PaymentPlanRequestDebtRepository planRequestDebts,DebtRepository debts,ExternalObligationRepository externalObligations,ObjectMapper json){this.outbox=outbox;this.taxpayers=taxpayers;this.concepts=concepts;this.planRequestDebts=planRequestDebts;this.debts=debts;this.externalObligations=externalObligations;this.json=json;}

    void exemptionRequested(ExemptionRequest request){TaxpayerReference taxpayer=taxpayer(request.taxpayerId);TaxConcept concept=concept(request.taxConceptId);var payload=new ConfirmedOutboundEvents.ExemptionRequestedPayload("exr-"+request.id,taxpayer.externalId,concept.code,request.reason,request.requestedPercentage,request.requestedFrom,request.requestedUntil);enqueueSimple("exemptionRequested",payload,"ExemptionRequest",request.id);}
    void exemptionApproved(ExemptionRequest request,Exemption exemption){TaxpayerReference taxpayer=taxpayer(request.taxpayerId);TaxConcept concept=concept(request.taxConceptId);var payload=new ConfirmedOutboundEvents.UpdateExemptionStatusPayload("exr-"+request.id,taxpayer.externalId,"APPROVED","ex-"+exemption.id,concept.code,exemption.percentage,exemption.validFrom,exemption.validUntil,null);enqueueSimple("updateExemptionStatus",payload,"ExemptionRequest",request.id);}
    void exemptionRejected(ExemptionRequest request){TaxpayerReference taxpayer=taxpayer(request.taxpayerId);TaxConcept concept=concept(request.taxConceptId);var payload=new ConfirmedOutboundEvents.UpdateExemptionStatusPayload("exr-"+request.id,taxpayer.externalId,"REJECTED",null,concept.code,null,null,null,request.resolutionReason);enqueueSimple("updateExemptionStatus",payload,"ExemptionRequest",request.id);}

    void paymentPlanRequested(PaymentPlanRequest request){List<String> debtIds=planRequestDebts.findByRequestId(request.id).stream().map(x->"debt-"+x.debtId).toList();var payload=new ConfirmedOutboundEvents.PaymentPlanRequestedPayload("ppr-"+request.id,taxpayer(request.taxpayerId).externalId,debtIds,request.totalDebtAtRequest,request.requestedInstallments);enqueueSimple("paymentPlanRequested",payload,"PaymentPlanRequest",request.id);}
    void paymentPlanGranted(PaymentPlanRequest request,PaymentPlan plan){var payload=new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload("ppr-"+request.id,taxpayer(request.taxpayerId).externalId,"GRANTED","plan-"+plan.id,plan.installmentCount,plan.totalPlanAmount,null);enqueueSimple("updatePaymentPlanStatus",payload,"PaymentPlanRequest",request.id);}
    void paymentPlanRejected(PaymentPlanRequest request){var payload=new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload("ppr-"+request.id,taxpayer(request.taxpayerId).externalId,"REJECTED",null,null,null,request.resolutionReason);enqueueSimple("updatePaymentPlanStatus",payload,"PaymentPlanRequest",request.id);}

    void overdueDebt(Debt debt,LocalDate processingDate){
        if(!debt.isCollectible()||!debt.dueDate.isBefore(processingDate))return;
        Debt lockedDebt=debts.findByIdForUpdate(debt.id).orElseThrow(()->CatalogService.notFound("Deuda"));
        if(!lockedDebt.isCollectible()||!lockedDebt.dueDate.isBefore(processingDate))return;
        String idempotencyKey="overdueDebt:Debt:"+lockedDebt.id;
        if(outbox.existsByIdempotencyKey(idempotencyKey))return;
        TaxpayerReference taxpayer=taxpayer(lockedDebt.taxpayerId);
        if(taxpayer.taxpayerType!=TaxpayerType.CITIZEN||taxpayer.externalId==null||taxpayer.externalId.isBlank())return;
        TaxConcept concept=concept(lockedDebt.taxConceptId);
        var payload=new ConfirmedOutboundEvents.OverdueDebtPayload("debt-"+lockedDebt.id,taxpayer.externalId,concept.code,lockedDebt.outstandingBalance,lockedDebt.dueDate);
        String aggregateId=String.valueOf(lockedDebt.id);var event=core("overdueDebt",payload,"Debt",aggregateId);
        OutboxEvent entity=new OutboxEvent();entity.id=UUID.fromString(event.eventId());entity.eventType=event.eventType();entity.targetModule="CORE";entity.aggregateType="Debt";entity.aggregateId=aggregateId;entity.idempotencyKey=idempotencyKey;entity.payload=write(event);entity.status=OutboxStatus.PENDING;entity.retryCount=0;entity.createdAt=OffsetDateTime.now();outbox.save(entity);
    }
    void paymentRegistered(Payment payment,PaymentAllocation allocation,Debt debt){externalOrigin(debt).ifPresent(origin->{var payload=new ConfirmedOutboundEvents.ExternalPaymentRegisteredPayload("payment-"+payment.id,"debt-"+debt.id,originType(origin.externalType()),origin.externalReferenceId(),infractionId(origin),allocation.amount,payment.paidAt,debt.outstandingBalance);enqueue("PaymentAllocation",allocation.id.toString(),core("paymentRegistered",payload,"Debt",debt.id.toString()));});}
    void paymentReversed(Payment payment,PaymentAllocation allocation,Debt debt,String reason){externalOrigin(debt).ifPresent(origin->{var payload=new ConfirmedOutboundEvents.PaymentReversedPayload("payment-"+payment.id,"debt-"+debt.id,originType(origin.externalType()),origin.externalReferenceId(),infractionId(origin),allocation.amount,allocation.reversedAt,debt.outstandingBalance,reason);enqueue("PaymentAllocation",allocation.id.toString(),core("paymentReversed",payload,"Debt",debt.id.toString()));});}
    void debtSettled(Debt debt){TaxpayerReference taxpayer=taxpayer(debt.taxpayerId);TaxConcept concept=concept(debt.taxConceptId);OffsetDateTime settledAt=OffsetDateTime.now();var origin=externalOrigin(debt);String originType=origin.map(x->originType(x.externalType())).orElse("MUNICIPAL_TAX");String originId=origin.map(ExternalDebtOrigin::externalReferenceId).orElse("liq-"+debt.liquidationId);var payload=new ConfirmedOutboundEvents.DebtSettledPayload("debt-"+debt.id,taxpayer.taxpayerType==TaxpayerType.CITIZEN?taxpayer.externalId:null,concept.code,originType,originId,origin.flatMap(x->Optional.ofNullable(infractionId(x))).orElse(null),settledAt,BigDecimal.ZERO.setScale(2),BigDecimal.ZERO.setScale(2));enqueueSimple("debtSettled",payload,"Debt",debt.id);}

    Optional<ExternalDebtOrigin> externalOrigin(Debt debt){if(debt.originType!=DebtOriginType.EXTERNAL_OBLIGATION||debt.externalObligationId==null)return Optional.empty();ExternalObligation external=externalObligations.findById(debt.externalObligationId).orElseThrow(()->CatalogService.notFound("Obligación externa"));String source=external.sourceModule.trim().toLowerCase(Locale.ROOT);String target=switch(source){case "m4","habilitaciones"->"M4";case "m7","transito"->"M7";default->null;};return target==null?Optional.empty():Optional.of(new ExternalDebtOrigin(target,external.externalReferenceId,external.externalType));}
    record ExternalDebtOrigin(String targetModule,String externalReferenceId,ExternalObligationType externalType) {}

    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.M2UpdateTicketStatusData> ticketStatus(String ticketId,String updateType,String publicMessage,String internalMessage,JsonNode details,String userId){return ticketStatus(ticketId,updateType,publicMessage,internalMessage,details,userId,null,null);}
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.M2UpdateTicketStatusData> ticketStatus(String ticketId,String updateType,String publicMessage,String internalMessage,JsonNode details,String userId,String correlationId,String causationId){OffsetDateTime at=OffsetDateTime.now();var data=new ConfirmedOutboundEvents.M2UpdateTicketStatusData(ticketId,updateType,publicMessage,internalMessage,null,details,List.of(),new ConfirmedOutboundEvents.UpdatedBy("EXTERNAL_USER",userId),at);var initial=core("updateTicketStatus",data,"TicketCase",ticketId);return new ConfirmedOutboundEvents.CoreEnvelope<>(initial.eventId(),initial.eventType(),initial.eventVersion(),initial.occurredAt(),initial.sourceModule(),correlationId==null?initial.correlationId():correlationId,causationId,data);}
    void ticketStatus(String ticketId,String aggregateId,String updateType,String publicMessage,String internalMessage,JsonNode details,String userId,String correlationId,String causationId){var event=ticketStatus(ticketId,updateType,publicMessage,internalMessage,details,userId,correlationId,causationId);enqueue(isUuid(ticketId)?"CORE":"PLATFORM","TicketCase",aggregateId,event);}

    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.UpdateExemptionStatusPayload> exemptionStatusEvent(ConfirmedOutboundEvents.UpdateExemptionStatusPayload payload){return core("updateExemptionStatus",payload,"ExemptionRequest",payload.requestId());}
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.ExemptionRequestedPayload> exemptionRequestedEvent(ConfirmedOutboundEvents.ExemptionRequestedPayload payload){return core("exemptionRequested",payload,"ExemptionRequest",payload.requestId());}
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.PaymentPlanRequestedPayload> paymentPlanRequestedEvent(ConfirmedOutboundEvents.PaymentPlanRequestedPayload payload){return core("paymentPlanRequested",payload,"PaymentPlanRequest",payload.requestId());}
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload> paymentPlanStatusEvent(ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload payload){return core("updatePaymentPlanStatus",payload,"PaymentPlanRequest",payload.requestId());}
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.OverdueDebtPayload> overdueDebtEvent(ConfirmedOutboundEvents.OverdueDebtPayload payload){return core("overdueDebt",payload,"Debt",payload.debtId());}
    ConfirmedOutboundEvents.CoreEnvelope<ConfirmedOutboundEvents.DebtSettledPayload> debtSettledEvent(ConfirmedOutboundEvents.DebtSettledPayload payload){return core("debtSettled",payload,"Debt",payload.debtId());}

    private <T> void enqueueSimple(String type,T payload,String aggregate,Object aggregateId){var event=core(type,payload,aggregate,String.valueOf(aggregateId));enqueue(aggregate,String.valueOf(aggregateId),event);}
    private <T> ConfirmedOutboundEvents.CoreEnvelope<T> core(String type,T data,String aggregate,String aggregateId){return new ConfirmedOutboundEvents.CoreEnvelope<>(UUID.randomUUID().toString(),type,"1.0",OffsetDateTime.now(),"rentas",correlation(aggregate,aggregateId),null,data);}
    private String correlation(String aggregate,String id){return UUID.nameUUIDFromBytes((aggregate+":"+id).getBytes(StandardCharsets.UTF_8)).toString();}
    private void enqueue(String aggregate,String aggregateId,ConfirmedOutboundEvents.CoreEnvelope<?> event){enqueue("CORE",aggregate,aggregateId,event);}
    private void enqueue(String target,String aggregate,String aggregateId,ConfirmedOutboundEvents.CoreEnvelope<?> event){OutboxEvent entity=new OutboxEvent();entity.id=UUID.fromString(event.eventId());entity.eventType=event.eventType();entity.targetModule=target;entity.aggregateType=aggregate;entity.aggregateId=aggregateId;entity.payload=write(event);entity.status=OutboxStatus.PENDING;entity.retryCount=0;entity.createdAt=OffsetDateTime.now();outbox.save(entity);}
    private boolean isUuid(String value){try{UUID.fromString(value);return true;}catch(IllegalArgumentException ex){return false;}}
    private String originType(ExternalObligationType type){return type==ExternalObligationType.TRAFFIC_INFRACTION?"TRAFFIC_FINE":type.name();}
    private String infractionId(ExternalDebtOrigin origin){return origin.externalType()==ExternalObligationType.TRAFFIC_INFRACTION?origin.externalReferenceId():null;}
    private TaxpayerReference taxpayer(Long id){return taxpayers.findById(id).orElseThrow(()->CatalogService.notFound("Contribuyente"));}
    private TaxConcept concept(Long id){return concepts.findById(id).orElseThrow(()->CatalogService.notFound("Concepto"));}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new BusinessException("INVALID_EVENT_PAYLOAD","No se pudo serializar el evento saliente",422);}}
}
