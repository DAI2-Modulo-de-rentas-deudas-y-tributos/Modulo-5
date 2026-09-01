package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Service
class ConfirmedIntegrationOutbox {
    private final OutboxRepository outbox;private final TaxpayerRepository taxpayers;private final TaxConceptRepository concepts;
    private final PaymentPlanRequestDebtRepository planRequestDebts;private final DebtRepository debts;private final ObjectMapper json;
    ConfirmedIntegrationOutbox(OutboxRepository outbox,TaxpayerRepository taxpayers,TaxConceptRepository concepts,
        PaymentPlanRequestDebtRepository planRequestDebts,DebtRepository debts,ObjectMapper json){this.outbox=outbox;this.taxpayers=taxpayers;this.concepts=concepts;this.planRequestDebts=planRequestDebts;this.debts=debts;this.json=json;}

    void exemptionRequested(ExemptionRequest request){TaxpayerReference taxpayer=taxpayer(request.taxpayerId);TaxConcept concept=concept(request.taxConceptId);var payload=new ConfirmedOutboundEvents.ExemptionRequestedPayload("exr-"+request.id,taxpayer.externalId,concept.code,request.reason,request.requestedPercentage,request.requestedFrom,request.requestedUntil);enqueueSimple("M1","exemptionRequested",payload,"ExemptionRequest",request.id);enqueueSimple("M8","exemptionRequested",payload,"ExemptionRequest",request.id);}
    void exemptionApproved(ExemptionRequest request,Exemption exemption){TaxpayerReference taxpayer=taxpayer(request.taxpayerId);TaxConcept concept=concept(request.taxConceptId);var payload=new ConfirmedOutboundEvents.UpdateExemptionStatusPayload("exr-"+request.id,taxpayer.externalId,"APPROVED","ex-"+exemption.id,concept.code,exemption.percentage,exemption.validFrom,exemption.validUntil,null);enqueueSimple("M1","updateExemptionStatus",payload,"Exemption",exemption.id);enqueueSimple("M8","updateExemptionStatus",payload,"Exemption",exemption.id);}
    void exemptionRejected(ExemptionRequest request){TaxpayerReference taxpayer=taxpayer(request.taxpayerId);TaxConcept concept=concept(request.taxConceptId);var payload=new ConfirmedOutboundEvents.UpdateExemptionStatusPayload("exr-"+request.id,taxpayer.externalId,"REJECTED",null,concept.code,null,null,null,request.resolutionReason);enqueueSimple("M1","updateExemptionStatus",payload,"ExemptionRequest",request.id);enqueueSimple("M8","updateExemptionStatus",payload,"ExemptionRequest",request.id);}

    void paymentPlanRequested(PaymentPlanRequest request){List<String> debtIds=planRequestDebts.findByRequestId(request.id).stream().map(x->"debt-"+x.debtId).toList();var payload=new ConfirmedOutboundEvents.PaymentPlanRequestedPayload("ppr-"+request.id,taxpayer(request.taxpayerId).externalId,debtIds,request.totalDebtAtRequest,request.requestedInstallments);enqueueSimple("M1","paymentPlanRequested",payload,"PaymentPlanRequest",request.id);}
    void paymentPlanGranted(PaymentPlanRequest request,PaymentPlan plan){var payload=new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload("ppr-"+request.id,taxpayer(request.taxpayerId).externalId,"GRANTED","plan-"+plan.id,plan.installmentCount,plan.totalPlanAmount,null);enqueueSimple("M1","updatePaymentPlanStatus",payload,"PaymentPlan",plan.id);}
    void paymentPlanRejected(PaymentPlanRequest request){var payload=new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload("ppr-"+request.id,taxpayer(request.taxpayerId).externalId,"REJECTED",null,null,null,request.resolutionReason);enqueueSimple("M1","updatePaymentPlanStatus",payload,"PaymentPlanRequest",request.id);}

    void overdueDebt(Debt debt){TaxpayerReference taxpayer=taxpayer(debt.taxpayerId);TaxConcept concept=concept(debt.taxConceptId);var payload=new ConfirmedOutboundEvents.OverdueDebtPayload("debt-"+debt.id,taxpayer.externalId,concept.code,debt.outstandingBalance,debt.dueDate);enqueueSimple("M8","overdueDebt",payload,"Debt",debt.id);}
    void debtSettled(Debt debt){TaxpayerReference taxpayer=taxpayer(debt.taxpayerId);TaxConcept concept=concept(debt.taxConceptId);var payload=new ConfirmedOutboundEvents.DebtSettledPayload("debt-"+debt.id,taxpayer.externalId,concept.code,OffsetDateTime.now(),BigDecimal.ZERO.setScale(2));enqueueSimple("M8","debtSettled",payload,"Debt",debt.id);}

    ConfirmedOutboundEvents.M2Envelope<ConfirmedOutboundEvents.M2UpdateTicketStatusData> ticketStatus(Long ticketId,String updateType,String publicMessage,String internalMessage,JsonNode details,String userId){return new ConfirmedOutboundEvents.M2Envelope<>("1.0",UUID.randomUUID().toString(),"updateTicketStatus",OffsetDateTime.now(),new ConfirmedOutboundEvents.Producer("M5","rentas-api"),"tickets/"+ticketId,new ConfirmedOutboundEvents.M2UpdateTicketStatusData(ticketId,updateType,publicMessage,internalMessage,null,details,List.of(),new ConfirmedOutboundEvents.UpdatedBy("AREA_USER",userId),OffsetDateTime.now()));}
    void ticketStatus(Long ticketId,String aggregateId,String updateType,String publicMessage,String internalMessage,JsonNode details,String userId){var event=ticketStatus(ticketId,updateType,publicMessage,internalMessage,details,userId);enqueue("M2","TicketCase",aggregateId,event.eventId(),event);}

    ConfirmedOutboundEvents.SimpleEnvelope<ConfirmedOutboundEvents.UpdateExemptionStatusPayload> exemptionStatusEvent(ConfirmedOutboundEvents.UpdateExemptionStatusPayload payload){return simple("updateExemptionStatus",payload);}
    ConfirmedOutboundEvents.SimpleEnvelope<ConfirmedOutboundEvents.ExemptionRequestedPayload> exemptionRequestedEvent(ConfirmedOutboundEvents.ExemptionRequestedPayload payload){return simple("exemptionRequested",payload);}
    ConfirmedOutboundEvents.SimpleEnvelope<ConfirmedOutboundEvents.PaymentPlanRequestedPayload> paymentPlanRequestedEvent(ConfirmedOutboundEvents.PaymentPlanRequestedPayload payload){return simple("paymentPlanRequested",payload);}
    ConfirmedOutboundEvents.SimpleEnvelope<ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload> paymentPlanStatusEvent(ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload payload){return simple("updatePaymentPlanStatus",payload);}
    ConfirmedOutboundEvents.SimpleEnvelope<ConfirmedOutboundEvents.OverdueDebtPayload> overdueDebtEvent(ConfirmedOutboundEvents.OverdueDebtPayload payload){return simple("overdueDebt",payload);}
    ConfirmedOutboundEvents.SimpleEnvelope<ConfirmedOutboundEvents.DebtSettledPayload> debtSettledEvent(ConfirmedOutboundEvents.DebtSettledPayload payload){return simple("debtSettled",payload);}

    private <T> void enqueueSimple(String target,String type,T payload,String aggregate,Object aggregateId){var event=simple(type,payload);enqueue(target,aggregate,String.valueOf(aggregateId),event.eventId(),event);}
    private <T> ConfirmedOutboundEvents.SimpleEnvelope<T> simple(String type,T payload){return new ConfirmedOutboundEvents.SimpleEnvelope<>(UUID.randomUUID().toString(),type,OffsetDateTime.now(),"rentas",payload);}
    private void enqueue(String target,String aggregate,String aggregateId,String eventId,Object event){OutboxEvent entity=new OutboxEvent();entity.id=UUID.fromString(eventId);entity.eventType=((ConfirmedOutboundEvents.TypedEvent)event).eventType();entity.targetModule=target;entity.aggregateType=aggregate;entity.aggregateId=aggregateId;entity.payload=write(event);entity.status=OutboxStatus.PENDING;entity.retryCount=0;entity.createdAt=OffsetDateTime.now();outbox.save(entity);}
    private TaxpayerReference taxpayer(Long id){return taxpayers.findById(id).orElseThrow(()->CatalogService.notFound("Contribuyente"));}
    private TaxConcept concept(Long id){return concepts.findById(id).orElseThrow(()->CatalogService.notFound("Concepto"));}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new BusinessException("INVALID_EVENT_PAYLOAD","No se pudo serializar el evento saliente",422);}}
}
