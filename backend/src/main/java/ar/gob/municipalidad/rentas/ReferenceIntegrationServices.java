package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
class IntegrationInbox {
    private final ProcessedEventRepository processed;private final IntegrationEventLogRepository logs;private final ObjectMapper json;
    IntegrationInbox(ProcessedEventRepository processed,IntegrationEventLogRepository logs,ObjectMapper json){this.processed=processed;this.logs=logs;this.json=json;}
    boolean processed(UUID id){return processed.existsById(id);}
    IntegrationEventLog receive(ApiDtos.EventEnvelope<?> event){IntegrationEventLog log=new IntegrationEventLog();log.eventId=event.eventId();log.eventType=event.eventType();log.sourceModule=event.sourceModule();log.direction=EventDirection.INBOUND;log.status=IntegrationEventStatus.RECEIVED;log.retryCount=0;log.occurredAt=event.occurredAt();log.receivedAt=OffsetDateTime.now();try{log.payload=json.writeValueAsString(event);}catch(Exception ex){throw new BusinessException("INVALID_EVENT_PAYLOAD","No se pudo serializar el evento",422);}return logs.save(log);}
    void complete(ApiDtos.EventEnvelope<?> event,IntegrationEventLog log){ProcessedEvent done=new ProcessedEvent();done.eventId=event.eventId();done.eventType=event.eventType();done.sourceModule=event.sourceModule();done.receivedAt=log.receivedAt;done.processedAt=OffsetDateTime.now();processed.save(done);log.status=IntegrationEventStatus.PROCESSED;log.processedAt=done.processedAt;}
}

@Service
class TaxpayerIntegrationService {
    private final TaxpayerRepository taxpayers;private final IntegrationInbox inbox;
    TaxpayerIntegrationService(TaxpayerRepository taxpayers,IntegrationInbox inbox){this.taxpayers=taxpayers;this.inbox=inbox;}
    @Transactional TaxpayerReference consume(ApiDtos.EventEnvelope<ApiDtos.TaxpayerEventData> event){CatalogService.require(Set.of("taxpayerCreated","taxpayerUpdated","taxpayerSynchronized").contains(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Evento de M1 no soportado");ApiDtos.TaxpayerEventData d=event.data();Optional<TaxpayerReference> existing=taxpayers.findByTaxpayerTypeAndExternalId(d.taxpayerType(),d.externalId());if(inbox.processed(event.eventId()))return existing.orElseThrow();IntegrationEventLog log=inbox.receive(event);TaxpayerReference taxpayer=existing.orElseGet(TaxpayerReference::new);if(taxpayer.id==null){taxpayer.taxpayerType=d.taxpayerType();taxpayer.externalId=d.externalId();taxpayer.createdAt=OffsetDateTime.now();}taxpayer.dni=d.dni();taxpayer.cuit=d.cuit();taxpayer.displayName=d.displayName();taxpayer.externalStatus=d.status();taxpayer.updatedAt=OffsetDateTime.now();taxpayers.save(taxpayer);inbox.complete(event,log);return taxpayer;}
}

@Service
class TicketIntegrationService {
    private final TicketCaseRepository tickets;private final TaxpayerRepository taxpayers;private final IntegrationInbox inbox;
    TicketIntegrationService(TicketCaseRepository tickets,TaxpayerRepository taxpayers,IntegrationInbox inbox){this.tickets=tickets;this.taxpayers=taxpayers;this.inbox=inbox;}
    @Transactional TicketCase consume(ApiDtos.EventEnvelope<ApiDtos.TicketEventData> event){CatalogService.require(Set.of("ticketCreated","ticketUpdated").contains(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Evento de M2 no soportado");ApiDtos.TicketEventData d=event.data();Optional<TicketCase> existing=tickets.findByExternalTicketId(d.externalTicketId());if(inbox.processed(event.eventId()))return existing.orElseThrow();IntegrationEventLog log=inbox.receive(event);TicketCase ticket=existing.orElseGet(TicketCase::new);if(ticket.id==null){ticket.externalTicketId=d.externalTicketId();ticket.createdAt=d.createdAt()==null?event.occurredAt():d.createdAt();}ticket.externalCitizenId=d.externalCitizenId();ticket.taxpayerId=d.externalCitizenId()==null?null:taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,d.externalCitizenId()).map(x->x.id).orElse(null);ticket.category=d.category();ticket.description=d.description();ticket.priority=d.priority();ticket.status=d.status();ticket.updatedAt=OffsetDateTime.now();if(ticket.status==TicketCaseStatus.COMPLETED)ticket.completedAt=ticket.updatedAt;tickets.save(ticket);inbox.complete(event,log);return ticket;}
}

@Service
class TicketService {
    private final TicketCaseRepository tickets;private final TicketCaseUpdateRepository updates;private final CurrentIdentity identity;private final PaymentService events;private final AuditService audit;
    TicketService(TicketCaseRepository tickets,TicketCaseUpdateRepository updates,CurrentIdentity identity,PaymentService events,AuditService audit){this.tickets=tickets;this.updates=updates;this.identity=identity;this.events=events;this.audit=audit;}
    TicketCase get(Long id){return tickets.findById(id).orElseThrow(()->CatalogService.notFound("Ticket"));}
    @Transactional TicketCase assign(Long id){TicketCase t=get(id);mutable(t);t.assignedTo=identity.get().userId();t.status=TicketCaseStatus.IN_PROGRESS;t.updatedAt=OffsetDateTime.now();publish(t,"assigned");return t;}
    @Transactional TicketCase note(Long id,String message){TicketCase t=get(id);mutable(t);add(t,TicketUpdateType.INTERNAL_NOTE,message);return t;}
    @Transactional TicketCase requestInformation(Long id,String message){TicketCase t=get(id);mutable(t);add(t,TicketUpdateType.INFORMATION_REQUEST,message);t.status=TicketCaseStatus.WAITING_FOR_INFORMATION;publish(t,"informationRequested");return t;}
    @Transactional TicketCase complete(Long id,String resolution){TicketCase t=get(id);mutable(t);add(t,TicketUpdateType.RESOLUTION,resolution);t.status=TicketCaseStatus.COMPLETED;t.completedAt=t.updatedAt;publish(t,"completed");return t;}
    @Transactional TicketCase reject(Long id,String reason){TicketCase t=get(id);mutable(t);add(t,TicketUpdateType.REJECTION,reason);t.status=TicketCaseStatus.REJECTED;t.completedAt=t.updatedAt;publish(t,"rejected");return t;}
    private void add(TicketCase t,TicketUpdateType type,String message){TicketCaseUpdate u=new TicketCaseUpdate();u.ticketCaseId=t.id;u.type=type;u.message=message;u.createdBy=identity.get().userId();u.createdAt=OffsetDateTime.now();updates.save(u);t.updatedAt=u.createdAt;audit.record("TicketCase",t.id,"TICKET_"+type.name(),u);}
    private void mutable(TicketCase t){CatalogService.require(t.status!=TicketCaseStatus.COMPLETED&&t.status!=TicketCaseStatus.REJECTED,"TICKET_ALREADY_CLOSED","El ticket ya está cerrado");}
    private void publish(TicketCase t,String action){events.addOutbox("updateTicketStatus","M2","TicketCase",t.id,"{\"externalTicketId\":\""+t.externalTicketId+"\",\"status\":\""+t.status+"\",\"action\":\""+action+"\"}");}
}

@Service
class SocialBenefitIntegrationService {
    private final SocialBenefitRepository benefits;private final SocialBenefitTaxConceptRepository links;private final TaxpayerRepository taxpayers;private final TaxConceptRepository concepts;private final IntegrationInbox inbox;
    SocialBenefitIntegrationService(SocialBenefitRepository benefits,SocialBenefitTaxConceptRepository links,TaxpayerRepository taxpayers,TaxConceptRepository concepts,IntegrationInbox inbox){this.benefits=benefits;this.links=links;this.taxpayers=taxpayers;this.concepts=concepts;this.inbox=inbox;}
    @Transactional SocialBenefitReference consume(ApiDtos.EventEnvelope<ApiDtos.SocialBenefitEventData> event){CatalogService.require("socialBenefitUpdated".equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba socialBenefitUpdated");ApiDtos.SocialBenefitEventData d=event.data();Optional<SocialBenefitReference> existing=benefits.findByExternalBenefitId(d.externalBenefitId());if(inbox.processed(event.eventId()))return existing.orElseThrow();CatalogService.require(d.validUntil()==null||!d.validUntil().isBefore(d.validFrom()),"INVALID_VALIDITY_RANGE","La vigencia del beneficio es inválida");List<TaxConcept> selected=d.taxConceptCodes()==null?List.of():d.taxConceptCodes().stream().distinct().map(code->concepts.findByCode(code).orElseThrow(()->CatalogService.notFound("Concepto "+code))).toList();IntegrationEventLog log=inbox.receive(event);SocialBenefitReference benefit=existing.orElseGet(SocialBenefitReference::new);if(benefit.id==null)benefit.externalBenefitId=d.externalBenefitId();benefit.externalCitizenId=d.externalCitizenId();benefit.taxpayerId=taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,d.externalCitizenId()).map(x->x.id).orElse(null);benefit.benefitType=d.benefitType();benefit.externalStatus=d.status();benefit.discountPercentage=d.discountPercentage();benefit.validFrom=d.validFrom();benefit.validUntil=d.validUntil();benefit.sourceEventId=event.eventId();benefit.updatedAt=OffsetDateTime.now();benefits.save(benefit);links.deleteBySocialBenefitId(benefit.id);for(TaxConcept concept:selected){SocialBenefitTaxConcept link=new SocialBenefitTaxConcept();link.socialBenefitId=benefit.id;link.taxConceptId=concept.id;links.save(link);}inbox.complete(event,log);return benefit;}
}
