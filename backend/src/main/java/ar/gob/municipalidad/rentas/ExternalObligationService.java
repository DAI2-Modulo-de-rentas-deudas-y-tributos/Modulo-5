package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
class ExternalObligationService {
    private final IntegrationInbox inbox;private final IntegrationEventLogRepository logs;private final ExternalObligationRepository obligations;
    private final TaxpayerRepository taxpayers;private final TaxConceptRepository concepts;private final DebtRepository debts;
    ExternalObligationService(IntegrationInbox inbox,IntegrationEventLogRepository logs,ExternalObligationRepository obligations,TaxpayerRepository taxpayers,TaxConceptRepository concepts,DebtRepository debts){this.inbox=inbox;this.logs=logs;this.obligations=obligations;this.taxpayers=taxpayers;this.concepts=concepts;this.debts=debts;}
    @Transactional ExternalObligation consumeInfraction(ApiDtos.EventEnvelope<ApiDtos.InfractionData> event){ApiDtos.InfractionData d=event.data();return consume(new ApiDtos.EventEnvelope<>(event.eventId(),event.eventType(),event.occurredAt(),event.sourceModule(),new ApiDtos.ExternalObligationData(d.externalReferenceId(),d.taxpayerType(),d.taxpayerExternalId(),d.amount(),d.dueDate())),"infractionConfirmed",ExternalObligationType.TRAFFIC_INFRACTION,"TRAFFIC_INFRACTION");}
    @Transactional ExternalObligation consumeConfirmedInfraction(ConfirmedInboundEvents.M7InfractionConfirmedEvent event){var d=event.data();TaxpayerType type=d.debtorIdType()==ConfirmedInboundEvents.M7DebtorIdType.DNI?TaxpayerType.CITIZEN:TaxpayerType.ORGANIZATION;var normalized=new ApiDtos.EventEnvelope<>(event.eventId(),event.eventType(),event.occurredAt(),"M7",new ApiDtos.ExternalObligationData(d.infractionId().toString(),type,d.debtorId(),d.finalAmount(),d.infractionDateTime().toLocalDate()));return consume(normalized,"infractionConfirmed",ExternalObligationType.TRAFFIC_INFRACTION,"TRAFFIC_INFRACTION",event);}
    @Transactional ExternalObligation createM4(CoreM4Events.Envelope<?> event,ExternalObligationType type,
        String originId,String cuit,java.math.BigDecimal amount,java.time.LocalDate dueDate){
        Optional<ExternalObligation> existing=obligations.findBySourceModuleAndExternalTypeAndExternalReferenceId("habilitaciones",type,originId);
        if(existing.isPresent()){
            ExternalObligation obligation=existing.orElseThrow();
            if(obligation.status==ExternalObligationStatus.ERROR)process(obligation);
            return obligation;
        }
        ExternalObligation obligation=new ExternalObligation();obligation.sourceModule="habilitaciones";
        obligation.externalType=type;obligation.externalReferenceId=originId;obligation.sourceEventId=event.eventId();
        obligation.externalTaxpayerType=TaxpayerType.ORGANIZATION;obligation.externalTaxpayerId=cuit;
        obligation.amount=PaymentService.money(amount);obligation.dueDate=dueDate;
        obligation.status=ExternalObligationStatus.RECEIVED;obligation.retryCount=0;
        obligation.receivedAt=OffsetDateTime.now();obligations.save(obligation);process(obligation);
        return obligation;
    }
    @Transactional ExternalObligation retry(Long id){return retry(id,null);}
    @Transactional ExternalObligation retry(Long id,String ifMatch){ExternalObligation o=obligations.findByIdForUpdate(id).orElseThrow(()->CatalogService.notFound("Obligación externa"));EntityTags.requireIfMatch(EntityTags.externalObligation(ApiResponses.of(o)),ifMatch);CatalogService.require(o.status==ExternalObligationStatus.ERROR,"EXTERNAL_OBLIGATION_NOT_RETRYABLE","La obligación no está en error");o.retryCount++;process(o);IntegrationEventLog log=logs.findFirstByEventIdOrderByIdDesc(o.sourceEventId).orElse(null);if(log!=null){log.lastRetryAt=OffsetDateTime.now();log.retryCount=o.retryCount;log.errorMessage=o.errorMessage;log.processedAt=o.processedAt;if(o.status==ExternalObligationStatus.PROCESSED)inbox.complete(log);else inbox.fail(log,o.errorMessage);}return o;}
    private ExternalObligation consume(ApiDtos.EventEnvelope<ApiDtos.ExternalObligationData> event,String expected,ExternalObligationType type,String conceptCode){return consume(event,expected,type,conceptCode,event);}
    private ExternalObligation consume(ApiDtos.EventEnvelope<ApiDtos.ExternalObligationData> event,String expected,ExternalObligationType type,String conceptCode,Object raw){CatalogService.require(expected.equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba "+expected);Optional<ExternalObligation> existing=obligations.findBySourceModuleAndExternalTypeAndExternalReferenceId(event.sourceModule(),type,event.data().externalReferenceId());if(inbox.processed(event.eventId()))return existing.orElseThrow();IntegrationEventLog log=inbox.receive(event,raw);if(existing.isPresent()){ExternalObligation duplicate=existing.get();if(duplicate.status==ExternalObligationStatus.ERROR)process(duplicate);if(duplicate.status==ExternalObligationStatus.PROCESSED)inbox.complete(event,log);else inbox.fail(log,duplicate.errorMessage);return duplicate;}ExternalObligation o=new ExternalObligation();o.sourceModule=event.sourceModule();o.externalType=type;o.externalReferenceId=event.data().externalReferenceId();o.sourceEventId=event.eventId();o.externalTaxpayerType=event.data().taxpayerType();o.externalTaxpayerId=event.data().taxpayerExternalId();o.amount=PaymentService.money(event.data().amount());o.dueDate=event.data().dueDate();o.status=ExternalObligationStatus.RECEIVED;o.retryCount=0;o.receivedAt=log.receivedAt;obligations.save(o);process(o,conceptCode);if(o.status==ExternalObligationStatus.PROCESSED)inbox.complete(event,log);else inbox.fail(log,o.errorMessage);return o;}
    private void process(ExternalObligation o){process(o,o.externalType.name());}
    private void process(ExternalObligation o,String conceptCode){try{TaxpayerReference taxpayer=resolveTaxpayer(o);TaxConcept concept=concepts.findByCode(conceptCode).orElseThrow(()->new BusinessException("TAX_CONCEPT_NOT_FOUND","Debe existir el concepto "+conceptCode,422));o.taxpayerId=taxpayer.id;o.taxConceptId=concept.id;if(!debts.existsByExternalObligationId(o.id)){Debt d=new Debt();d.taxpayerId=taxpayer.id;d.taxConceptId=concept.id;d.originType=DebtOriginType.EXTERNAL_OBLIGATION;d.externalObligationId=o.id;d.originalAmount=d.currentAmount=d.outstandingBalance=o.amount;d.dueDate=o.dueDate;d.status=DebtStatus.PENDING;d.createdAt=d.updatedAt=OffsetDateTime.now();debts.save(d);}o.status=ExternalObligationStatus.PROCESSED;o.errorMessage=null;o.processedAt=OffsetDateTime.now();}catch(BusinessException ex){o.status=ExternalObligationStatus.ERROR;o.errorMessage=ex.code+": "+ex.getMessage();o.processedAt=null;}}
    private TaxpayerReference resolveTaxpayer(ExternalObligation o){Optional<TaxpayerReference> found;if(o.externalType==ExternalObligationType.TRAFFIC_INFRACTION)found=o.externalTaxpayerType==TaxpayerType.CITIZEN?taxpayers.findByTaxpayerTypeAndDni(TaxpayerType.CITIZEN,o.externalTaxpayerId):taxpayers.findByTaxpayerTypeAndCuit(TaxpayerType.ORGANIZATION,o.externalTaxpayerId);else if("habilitaciones".equals(o.sourceModule))found=taxpayers.findByTaxpayerTypeAndCuit(TaxpayerType.ORGANIZATION,o.externalTaxpayerId);else found=taxpayers.findByTaxpayerTypeAndExternalId(o.externalTaxpayerType,o.externalTaxpayerId);return found.or(()->taxpayers.findByTaxpayerTypeAndExternalId(o.externalTaxpayerType,o.externalTaxpayerId)).orElseThrow(()->new BusinessException("TAXPAYER_REFERENCE_NOT_FOUND","No existe la referencia local del contribuyente para el identificador recibido",422));}
}
