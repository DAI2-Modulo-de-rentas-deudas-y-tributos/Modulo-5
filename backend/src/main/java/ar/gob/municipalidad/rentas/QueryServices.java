package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.*;
import java.time.*;
import java.util.*;

@Service
class IndicatorService {
    private final PaymentRepository payments;private final DebtRepository debts;
    IndicatorService(PaymentRepository payments,DebtRepository debts){this.payments=payments;this.debts=debts;}
    @Transactional(readOnly=true) ApiDtos.CollectionIndicatorResponse collection(LocalDate from,LocalDate to){CollectionIndicatorAggregate a=payments.aggregateConfirmed(from,to);return new ApiDtos.CollectionIndicatorResponse(a.getPaymentCount(),money(a.getConfirmedAmount()),money(a.getAllocatedAmount()),money(a.getUnallocatedAmount()));}
    @Transactional(readOnly=true) ApiDtos.DebtIndicatorResponse debt(){DebtIndicatorAggregate a=debts.aggregateDebt();return new ApiDtos.DebtIndicatorResponse(a.getDebtCount(),a.getPaidCount(),a.getOpenCount(),money(a.getOriginalAmount()),money(a.getOutstandingAmount()));}
    @Transactional(readOnly=true) ApiDtos.DelinquencyIndicatorResponse delinquency(){DelinquencyIndicatorAggregate a=debts.aggregateDelinquency(LocalDate.now());BigDecimal percentage=a.getOpenDebtCount()==0?BigDecimal.ZERO.setScale(2):BigDecimal.valueOf(a.getOverdueDebtCount()).multiply(new BigDecimal("100")).divide(BigDecimal.valueOf(a.getOpenDebtCount()),2,RoundingMode.HALF_UP);return new ApiDtos.DelinquencyIndicatorResponse(a.getOverdueDebtCount(),money(a.getOverdueAmount()),percentage);}
    @Transactional(readOnly=true) ApiDtos.IndicatorSummaryResponse summary(LocalDate from,LocalDate to){return new ApiDtos.IndicatorSummaryResponse(collection(from,to),debt(),delinquency());}
    private BigDecimal money(BigDecimal value){return MonetaryAmounts.normalize(value);}
}

@Service
class TaxpayerQueryService {
    private final TaxpayerRepository taxpayers;private final DebtRepository debts;private final PaymentRepository payments;private final PaymentPlanRepository plans;private final SocialBenefitRepository benefits;private final CurrentIdentity identity;
    TaxpayerQueryService(TaxpayerRepository taxpayers,DebtRepository debts,PaymentRepository payments,PaymentPlanRepository plans,SocialBenefitRepository benefits,CurrentIdentity identity){this.taxpayers=taxpayers;this.debts=debts;this.payments=payments;this.plans=plans;this.benefits=benefits;this.identity=identity;}
    @Transactional(readOnly=true) ApiDtos.TaxpayerSummaryResponse summary(Long id){taxpayers.findById(id).orElseThrow(()->CatalogService.notFound("Contribuyente"));identity.requireOwnership(id);List<Debt> taxpayerDebts=debts.findByTaxpayerId(id);BigDecimal outstanding=MonetaryAmounts.normalize(taxpayerDebts.stream().filter(Debt::isCollectible).map(x->x.outstandingBalance).reduce(BigDecimal.ZERO,BigDecimal::add));BigDecimal paid=MonetaryAmounts.normalize(payments.findByTaxpayerId(id).stream().filter(x->x.status==PaymentStatus.CONFIRMED).map(x->x.amount).reduce(BigDecimal.ZERO,BigDecimal::add));long open=taxpayerDebts.stream().filter(Debt::isCollectible).count();long activePlans=plans.countByTaxpayerIdAndStatus(id,PaymentPlanStatus.ACTIVE);long activeBenefits=benefits.findByTaxpayerId(id).stream().filter(x->x.calculatedStatus==SocialBenefitStatus.ACTIVE).count();return new ApiDtos.TaxpayerSummaryResponse(id,outstanding,paid,open,activePlans,activeBenefits);}
    @Transactional(readOnly=true) ApiDtos.DebtSummaryResponse debtSummary(Long id){taxpayers.findById(id).orElseThrow(()->CatalogService.notFound("Contribuyente"));identity.requireOwnership(id);List<Debt> all=debts.findByTaxpayerId(id);List<Debt> collectible=all.stream().filter(Debt::isCollectible).toList();long paid=all.stream().filter(x->x.status==DebtStatus.PAID).count();long overdue=collectible.stream().filter(x->x.dueDate.isBefore(LocalDate.now())).count();BigDecimal outstanding=MonetaryAmounts.normalize(collectible.stream().map(x->x.outstandingBalance).reduce(BigDecimal.ZERO,BigDecimal::add));return new ApiDtos.DebtSummaryResponse(id,all.size(),collectible.size(),paid,overdue,outstanding);}
}

@Service
class IntegrationReprocessService {
    private final IntegrationEventLogRepository logs;private final ProcessedEventRepository processedEvents;private final ObjectMapper json;private final ConfirmedM2Consumer coreM2;private final ConfirmedM8Consumer coreM8;private final ConfirmedM7Consumer m7;private final CoreM4Consumer coreM4;private final LegacyIntegrationReprocessAdapter legacy;private final TransactionTemplate transaction;
    IntegrationReprocessService(IntegrationEventLogRepository logs,ProcessedEventRepository processedEvents,ObjectMapper json,ConfirmedM2Consumer coreM2,ConfirmedM8Consumer coreM8,ConfirmedM7Consumer m7,CoreM4Consumer coreM4,LegacyIntegrationReprocessAdapter legacy,PlatformTransactionManager transactionManager){this.logs=logs;this.processedEvents=processedEvents;this.json=json;this.coreM2=coreM2;this.coreM8=coreM8;this.m7=m7;this.coreM4=coreM4;this.legacy=legacy;this.transaction=new TransactionTemplate(transactionManager);this.transaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);}
    IntegrationEventLog get(String eventId){return logs.findLatestNonRejectionByExternalEventId(eventId).or(()->logs.findFirstByExternalEventIdOrderByIdDesc(eventId)).or(()->{try{return logs.findFirstByEventIdOrderByIdDesc(UUID.fromString(eventId));}catch(IllegalArgumentException ex){return Optional.empty();}}).orElseThrow(()->CatalogService.notFound("Evento de integración"));}
    IntegrationEventLog reprocess(String eventId,String reason){return reprocess(eventId,reason,null);}
    IntegrationEventLog reprocess(String eventId,String reason,String ifMatch){
        try{transaction.execute(status->attempt(eventId,ifMatch));}
        catch(ReprocessAttemptFailure failure){transaction.execute(status->recordFailure(failure,reason));}
        return get(eventId);
    }
    private IntegrationEventLog attempt(String eventId,String ifMatch){
        IntegrationEventLog resolved=get(eventId);IntegrationEventLog original=logs.findByIdForUpdate(resolved.id).orElseThrow(()->CatalogService.notFound("Evento de integración"));
        if(original.businessKey!=null&&original.businessKey.startsWith("INBOUND_REJECTION:"))
            throw new BusinessException("INBOUND_REJECTION_NOT_REPROCESSABLE","El registro conserva metadatos sanitizados, no el payload original",409);
        if(processedEvents.existsByExternalEventId(original.externalEventId))return get(eventId);
        EntityTags.requireIfMatch(EntityTags.integrationEvent(ApiResponses.of(original)),ifMatch);
        CatalogService.require(original.status==IntegrationEventStatus.FAILED||original.status==IntegrationEventStatus.DEAD_LETTER,"INTEGRATION_EVENT_NOT_REPROCESSABLE","El evento no está en error");
        original.retryCount++;original.lastRetryAt=OffsetDateTime.now();
        try{
            if(!legacy.reprocessIfLegacy(original))reprocessConfirmed(original);
            return get(eventId);
        }catch(Exception ex){throw new ReprocessAttemptFailure(original.id,ex);}
    }
    private IntegrationEventLog recordFailure(ReprocessAttemptFailure failure,String reason){
        IntegrationEventLog original=logs.findByIdForUpdate(failure.logId).orElseThrow(()->CatalogService.notFound("Evento de integración"));
        if(processedEvents.existsByExternalEventId(original.externalEventId))return get(original.externalEventId);
        original.retryCount++;original.lastRetryAt=OffsetDateTime.now();original.status=original.retryCount>=5?IntegrationEventStatus.DEAD_LETTER:IntegrationEventStatus.FAILED;
        original.errorMessage=(reason==null?"":reason+": ")+failure.getCause().getMessage();return original;
    }
    private static final class ReprocessAttemptFailure extends RuntimeException {
        private final Long logId;
        private ReprocessAttemptFailure(Long logId,Exception cause){super(cause);this.logId=logId;}
    }
    private void reprocessConfirmed(IntegrationEventLog original)throws Exception{
        switch(original.eventType){
            case "permitFeeGenerated"->coreM4.consumePermitFee(json.readValue(original.payload,new TypeReference<CoreM4Events.Envelope<CoreM4Events.PermitFeeGeneratedData>>(){}));
            case "commercialFineGenerated"->coreM4.consumeCommercialFine(json.readValue(original.payload,new TypeReference<CoreM4Events.Envelope<CoreM4Events.CommercialFineGeneratedData>>(){}));
            case "infractionConfirmed"->m7.consume(json.readValue(original.payload,ConfirmedInboundEvents.M7InfractionConfirmedEvent.class));
            case "ticketUpdated"->coreM2.consume(json.readValue(original.payload,ConfirmedInboundEvents.M2TicketUpdatedEvent.class));
            case "socialBenefitUpdated"->coreM8.consume(json.readValue(original.payload,ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent.class));
            default->throw new BusinessException("UNSUPPORTED_EVENT_TYPE","No hay reprocesador para "+original.eventType,422);
        }
    }
}
