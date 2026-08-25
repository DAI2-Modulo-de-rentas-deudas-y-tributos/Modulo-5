package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Optional;

interface EventPublisher { void publish(String eventType, String targetModule, String payload); }

@Component
class LocalLogEventPublisher implements EventPublisher {
    private static final Logger log=LoggerFactory.getLogger(LocalLogEventPublisher.class);
    public void publish(String eventType,String targetModule,String payload){log.info("BROKER_ADAPTER local-log eventType={} targetModule={} payload={}",eventType,targetModule,payload);}
}

@Service
class ExternalObligationService {
    private final ProcessedEventRepository processed; private final IntegrationEventLogRepository logs; private final ExternalObligationRepository obligations;
    private final TaxpayerRepository taxpayers; private final TaxConceptRepository concepts; private final DebtRepository debts; private final ObjectMapper json;
    ExternalObligationService(ProcessedEventRepository processed,IntegrationEventLogRepository logs,ExternalObligationRepository obligations,TaxpayerRepository taxpayers,TaxConceptRepository concepts,DebtRepository debts,ObjectMapper json){this.processed=processed;this.logs=logs;this.obligations=obligations;this.taxpayers=taxpayers;this.concepts=concepts;this.debts=debts;this.json=json;}
    @Transactional ExternalObligation consumeInfraction(ApiDtos.EventEnvelope<ApiDtos.InfractionData> event){ApiDtos.InfractionData d=event.data();return consume(new ApiDtos.EventEnvelope<>(event.eventId(),event.eventType(),event.occurredAt(),event.sourceModule(),new ApiDtos.ExternalObligationData(d.externalReferenceId(),d.taxpayerType(),d.taxpayerExternalId(),d.amount(),d.dueDate())),"infractionConfirmed",ExternalObligationType.TRAFFIC_INFRACTION,"TRAFFIC_INFRACTION");}
    @Transactional ExternalObligation consumePermitFee(ApiDtos.EventEnvelope<ApiDtos.ExternalObligationData> event){return consume(event,"permitFeeGenerated",ExternalObligationType.PERMIT_FEE,"PERMIT_FEE");}
    @Transactional ExternalObligation consumeCommercialFine(ApiDtos.EventEnvelope<ApiDtos.ExternalObligationData> event){return consume(event,"commercialFineGenerated",ExternalObligationType.COMMERCIAL_FINE,"COMMERCIAL_FINE");}
    @Transactional ExternalObligation retry(Long id){ExternalObligation o=obligations.findById(id).orElseThrow(()->CatalogService.notFound("Obligación externa"));CatalogService.require(o.status==ExternalObligationStatus.ERROR,"EXTERNAL_OBLIGATION_NOT_RETRYABLE","La obligación no está en error");o.retryCount++;process(o);IntegrationEventLog log=logs.findFirstByEventIdOrderByIdDesc(o.sourceEventId).orElse(null);if(log!=null){log.lastRetryAt=OffsetDateTime.now();log.retryCount=o.retryCount;log.status=o.status==ExternalObligationStatus.PROCESSED?IntegrationEventStatus.PROCESSED:IntegrationEventStatus.FAILED;log.errorMessage=o.errorMessage;log.processedAt=o.processedAt;}if(o.status==ExternalObligationStatus.PROCESSED&&log!=null&&!processed.existsById(o.sourceEventId))markProcessed(o.sourceEventId,log.eventType,o.sourceModule,log);return o;}
    private ExternalObligation consume(ApiDtos.EventEnvelope<ApiDtos.ExternalObligationData> event,String expected,ExternalObligationType type,String conceptCode){CatalogService.require(expected.equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba "+expected);Optional<ExternalObligation> existing=obligations.findBySourceModuleAndExternalTypeAndExternalReferenceId(event.sourceModule(),type,event.data().externalReferenceId());if(processed.existsById(event.eventId()))return existing.orElseThrow();IntegrationEventLog log=received(event);if(existing.isPresent()){ExternalObligation duplicate=existing.get();if(duplicate.status==ExternalObligationStatus.ERROR)process(duplicate);if(duplicate.status==ExternalObligationStatus.PROCESSED)markProcessed(event,log);else fail(log,duplicate.errorMessage);return duplicate;}ExternalObligation o=new ExternalObligation();o.sourceModule=event.sourceModule();o.externalType=type;o.externalReferenceId=event.data().externalReferenceId();o.sourceEventId=event.eventId();o.externalTaxpayerType=event.data().taxpayerType();o.externalTaxpayerId=event.data().taxpayerExternalId();o.amount=PaymentService.money(event.data().amount());o.dueDate=event.data().dueDate();o.status=ExternalObligationStatus.RECEIVED;o.retryCount=0;o.receivedAt=log.receivedAt;obligations.save(o);process(o,conceptCode);if(o.status==ExternalObligationStatus.PROCESSED)markProcessed(event,log);else fail(log,o.errorMessage);return o;}
    private void process(ExternalObligation o){String code=switch(o.externalType){case TRAFFIC_INFRACTION->"TRAFFIC_INFRACTION";case PERMIT_FEE->"PERMIT_FEE";case COMMERCIAL_FINE->"COMMERCIAL_FINE";};process(o,code);}
    private void process(ExternalObligation o,String conceptCode){try{TaxpayerReference taxpayer=taxpayers.findByTaxpayerTypeAndExternalId(o.externalTaxpayerType,o.externalTaxpayerId).orElseThrow(()->new BusinessException("TAXPAYER_REFERENCE_NOT_FOUND","No existe la referencia local del contribuyente",422));TaxConcept concept=concepts.findByCode(conceptCode).orElseThrow(()->new BusinessException("TAX_CONCEPT_NOT_FOUND","Debe existir el concepto "+conceptCode,422));o.taxpayerId=taxpayer.id;o.taxConceptId=concept.id;if(!debts.existsByExternalObligationId(o.id)){Debt d=new Debt();d.taxpayerId=taxpayer.id;d.taxConceptId=concept.id;d.originType=DebtOriginType.EXTERNAL_OBLIGATION;d.externalObligationId=o.id;d.originalAmount=d.currentAmount=d.outstandingBalance=o.amount;d.dueDate=o.dueDate;d.status=DebtStatus.PENDING;d.createdAt=d.updatedAt=OffsetDateTime.now();debts.save(d);}o.status=ExternalObligationStatus.PROCESSED;o.errorMessage=null;o.processedAt=OffsetDateTime.now();}catch(BusinessException ex){o.status=ExternalObligationStatus.ERROR;o.errorMessage=ex.code+": "+ex.getMessage();o.processedAt=null;}}
    private IntegrationEventLog received(ApiDtos.EventEnvelope<?> event){IntegrationEventLog log=new IntegrationEventLog();log.eventId=event.eventId();log.eventType=event.eventType();log.sourceModule=event.sourceModule();log.direction=EventDirection.INBOUND;log.status=IntegrationEventStatus.RECEIVED;log.retryCount=0;log.occurredAt=event.occurredAt();log.receivedAt=OffsetDateTime.now();try{log.payload=json.writeValueAsString(event);}catch(Exception e){throw new BusinessException("INVALID_EVENT_PAYLOAD","No se pudo serializar el evento",422);}return logs.save(log);}
    private void fail(IntegrationEventLog log,String message){log.status=IntegrationEventStatus.FAILED;log.errorMessage=message;}
    private void markProcessed(ApiDtos.EventEnvelope<?> event,IntegrationEventLog log){ProcessedEvent p=new ProcessedEvent();p.eventId=event.eventId();p.eventType=event.eventType();p.sourceModule=event.sourceModule();p.receivedAt=log.receivedAt;p.processedAt=OffsetDateTime.now();processed.save(p);log.status=IntegrationEventStatus.PROCESSED;log.processedAt=p.processedAt;}
    private void markProcessed(java.util.UUID eventId,String eventType,String sourceModule,IntegrationEventLog log){ProcessedEvent p=new ProcessedEvent();p.eventId=eventId;p.eventType=eventType;p.sourceModule=sourceModule;p.receivedAt=log.receivedAt;p.processedAt=OffsetDateTime.now();processed.save(p);log.status=IntegrationEventStatus.PROCESSED;log.processedAt=p.processedAt;}
}

@Service
class OutboxPublisher {
    private final OutboxRepository repository; private final EventPublisher publisher;
    OutboxPublisher(OutboxRepository repository,EventPublisher publisher){this.repository=repository;this.publisher=publisher;}
    @Scheduled(fixedDelayString="${rentas.outbox-delay-ms:5000}")
    @Transactional void publishPending(){for(OutboxEvent event:repository.findPublishable(org.springframework.data.domain.PageRequest.of(0,50))){try{event.lastAttemptAt=OffsetDateTime.now();publisher.publish(event.eventType,event.targetModule,event.payload);event.status=OutboxStatus.PUBLISHED;event.publishedAt=OffsetDateTime.now();event.errorMessage=null;}catch(RuntimeException ex){event.retryCount++;event.errorMessage=ex.getMessage();event.status=event.retryCount>=5?OutboxStatus.DEAD_LETTER:OutboxStatus.FAILED;}}}
}
