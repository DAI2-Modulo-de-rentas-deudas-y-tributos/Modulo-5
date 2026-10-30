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
    boolean processed(UUID id){return processed(id.toString());}
    boolean processed(String id){return processed.existsByExternalEventId(id);}
    IntegrationEventLog receive(ApiDtos.EventEnvelope<?> event){return receive(normalize(event),event,false);}
    IntegrationEventLog receive(NormalizedIntegrationEvent event,Object raw){return receive(event,raw,false);}
    IntegrationEventLog ignore(NormalizedIntegrationEvent event){IntegrationEventLog log=receive(event,null,true);complete(event,log,IntegrationEventStatus.IGNORED);return log;}
    void fail(IntegrationEventLog log,String message){log.status=IntegrationEventStatus.FAILED;log.errorMessage=message;}
    void complete(ApiDtos.EventEnvelope<?> event,IntegrationEventLog log){complete(normalize(event),log,IntegrationEventStatus.PROCESSED);}
    void complete(NormalizedIntegrationEvent event,IntegrationEventLog log){complete(event,log,IntegrationEventStatus.PROCESSED);}
    private IntegrationEventLog receive(NormalizedIntegrationEvent event,Object raw,boolean minimal){IntegrationEventLog log=new IntegrationEventLog();log.eventId=technicalId(event.eventId());log.externalEventId=event.eventId();log.eventType=event.eventType();log.sourceModule=event.sourceModule();log.direction=EventDirection.INBOUND;log.status=IntegrationEventStatus.RECEIVED;log.retryCount=0;log.occurredAt=event.occurredAt();log.receivedAt=OffsetDateTime.now();try{log.payload=minimal?"{}":json.writeValueAsString(raw);}catch(Exception ex){throw new BusinessException("INVALID_EVENT_PAYLOAD","No se pudo serializar el evento",422);}return logs.save(log);}
    private void complete(NormalizedIntegrationEvent event,IntegrationEventLog log,IntegrationEventStatus status){ProcessedEvent done=new ProcessedEvent();done.eventId=technicalId(event.eventId());done.externalEventId=event.eventId();done.eventType=event.eventType();done.sourceModule=event.sourceModule();done.receivedAt=log.receivedAt;done.processedAt=OffsetDateTime.now();processed.save(done);log.status=status;log.processedAt=done.processedAt;}
    private NormalizedIntegrationEvent normalize(ApiDtos.EventEnvelope<?> event){return new NormalizedIntegrationEvent(event.eventId().toString(),event.eventType(),event.occurredAt(),event.sourceModule(),null,json.valueToTree(event.data()),null);}
    static UUID technicalId(String externalId){try{return UUID.fromString(externalId);}catch(IllegalArgumentException ex){return UUID.nameUUIDFromBytes(externalId.getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
}

@Service
class TaxpayerIntegrationService {
    private final TaxpayerRepository taxpayers;private final IntegrationInbox inbox;
    TaxpayerIntegrationService(TaxpayerRepository taxpayers,IntegrationInbox inbox){this.taxpayers=taxpayers;this.inbox=inbox;}
    @Transactional TaxpayerReference consume(ApiDtos.EventEnvelope<ApiDtos.TaxpayerEventData> event){CatalogService.require(Set.of("taxpayerCreated","taxpayerUpdated","taxpayerSynchronized").contains(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Evento de M1 no soportado");ApiDtos.TaxpayerEventData d=event.data();Optional<TaxpayerReference> existing=taxpayers.findByTaxpayerTypeAndExternalId(d.taxpayerType(),d.externalId());if(inbox.processed(event.eventId()))return existing.orElseThrow();IntegrationEventLog log=inbox.receive(event);TaxpayerReference taxpayer=existing.orElseGet(TaxpayerReference::new);if(taxpayer.id==null){taxpayer.taxpayerType=d.taxpayerType();taxpayer.externalId=d.externalId();taxpayer.createdAt=OffsetDateTime.now();}taxpayer.dni=d.dni();taxpayer.cuit=d.cuit();taxpayer.displayName=d.displayName();taxpayer.externalStatus=d.status();taxpayer.updatedAt=OffsetDateTime.now();taxpayers.save(taxpayer);inbox.complete(event,log);return taxpayer;}
}

@Service
class TicketIntegrationService {
    private final TicketCaseRepository tickets;private final TicketCaseUpdateRepository updates;private final TaxpayerRepository taxpayers;private final IntegrationInbox inbox;private final ConfirmedContractValidator validator;
    TicketIntegrationService(TicketCaseRepository tickets,TicketCaseUpdateRepository updates,TaxpayerRepository taxpayers,IntegrationInbox inbox,ConfirmedContractValidator validator){this.tickets=tickets;this.updates=updates;this.taxpayers=taxpayers;this.inbox=inbox;this.validator=validator;}
    @Transactional TicketCase consume(ApiDtos.EventEnvelope<ApiDtos.TicketEventData> event){
        validator.validate(event);CatalogService.require("M2".equals(event.sourceModule()),"INVALID_SOURCE_MODULE","Los eventos de tickets sólo admiten sourceModule M2");CatalogService.require(Set.of("ticketCreated","ticketUpdated").contains(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Evento de M2 no soportado");
        ApiDtos.TicketEventData data=event.data();validator.validate(data);String externalTicketId=data.ticketId().toString();Optional<TicketCase> existing=tickets.findByExternalTicketId(externalTicketId);if(inbox.processed(event.eventId()))return existing.orElseThrow();
        validateData(event.eventType(),data,existing);
        IntegrationEventLog log=inbox.receive(event);TicketCase ticket=switch(event.eventType()){case "ticketCreated"->createOrUpdate(event,data,existing,externalTicketId);case "ticketUpdated"->addInformation(event,data,existing);default->throw new IllegalStateException("Tipo de evento validado previamente");};inbox.complete(event,log);return ticket;
    }
    private void validateData(String eventType,ApiDtos.TicketEventData data,Optional<TicketCase> existing){
        if("ticketCreated".equals(eventType)){CatalogService.require(data.citizenId()!=null,"INVALID_EVENT","ticketCreated requiere citizenId");CatalogService.require(data.category()!=null&&!data.category().isBlank(),"INVALID_EVENT","ticketCreated requiere category");CatalogService.require(data.description()!=null&&!data.description().isBlank(),"INVALID_EVENT","ticketCreated requiere description");CatalogService.require(data.priority()!=null,"INVALID_EVENT","ticketCreated requiere priority");}
        else{CatalogService.require(data.additionalInformation()!=null&&!data.additionalInformation().isBlank(),"INVALID_EVENT","ticketUpdated requiere additionalInformation");CatalogService.require(existing.isPresent(),"NOT_FOUND","Ticket externo "+data.ticketId()+" no encontrado");}
    }
    private TicketCase createOrUpdate(ApiDtos.EventEnvelope<ApiDtos.TicketEventData> event,ApiDtos.TicketEventData data,Optional<TicketCase> existing,String externalTicketId){
        TicketCase ticket=existing.orElseGet(TicketCase::new);if(ticket.id==null){ticket.externalTicketId=externalTicketId;ticket.status=TicketCaseStatus.OPEN;ticket.createdAt=event.occurredAt();}ticket.externalCitizenId=data.citizenId().toString();ticket.taxpayerId=taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,ticket.externalCitizenId).map(x->x.id).orElse(null);ticket.category=data.category();ticket.description=data.description();ticket.priority=data.priority();ticket.updatedAt=event.occurredAt();return tickets.save(ticket);
    }
    private TicketCase addInformation(ApiDtos.EventEnvelope<ApiDtos.TicketEventData> event,ApiDtos.TicketEventData data,Optional<TicketCase> existing){
        TicketCase ticket=existing.orElseThrow();TicketCaseUpdate update=new TicketCaseUpdate();update.ticketCaseId=ticket.id;update.type=TicketUpdateType.INTERNAL_NOTE;update.message=data.additionalInformation();update.createdBy="M2";update.createdAt=event.occurredAt();updates.save(update);ticket.updatedAt=event.occurredAt();return tickets.save(ticket);
    }
}

@Service
class TicketService {
    private static final Set<String> RETURN_REASONS=Set.of("INVALID_INFORMATION","REQUEST_TYPE_MISMATCH","INSUFFICIENT_CONTEXT","OTHER");
    private final TicketCaseRepository tickets;private final TicketCaseUpdateRepository updates;private final CurrentIdentity identity;private final ConfirmedIntegrationOutbox events;private final IntegrationEventLogRepository integrationLogs;private final AuditService audit;private final ObjectMapper json;
    TicketService(TicketCaseRepository tickets,TicketCaseUpdateRepository updates,CurrentIdentity identity,ConfirmedIntegrationOutbox events,IntegrationEventLogRepository integrationLogs,AuditService audit,ObjectMapper json){this.tickets=tickets;this.updates=updates;this.identity=identity;this.events=events;this.integrationLogs=integrationLogs;this.audit=audit;this.json=json;}
    TicketCase get(Long id){return tickets.findById(id).orElseThrow(()->CatalogService.notFound("Ticket"));}
    private TicketCase getForUpdate(Long id){return tickets.findByIdForUpdate(id).orElseThrow(()->CatalogService.notFound("Ticket"));}
    @Transactional TicketCase assign(Long id){TicketCase t=getForUpdate(id);requireStatus(t,TicketCaseStatus.OPEN);CatalogService.require(t.assignedTo==null,"INVALID_TICKET_TRANSITION","El ticket ya tiene responsable");String cause=isOfficialTicket(t)?routedCause(t):null;t.assignedTo=identity.get().userId();t.status=TicketCaseStatus.IN_PROGRESS;t.updatedAt=OffsetDateTime.now();publish(t,"STARTED","Rentas inició la gestión del caso.",null,null,cause);return t;}
    @Transactional TicketCase note(Long id,String message){TicketCase t=getForUpdate(id);if(isOfficialTicket(t)){requireStatus(t,TicketCaseStatus.IN_PROGRESS);requireM2Thread(t);add(t,TicketUpdateType.PROGRESS,message);publish(t,"PROGRESS",null,message,null,null);}else{mutable(t);add(t,TicketUpdateType.INTERNAL_NOTE,message);}return t;}
    @Transactional TicketCase requestInformation(Long id,String message){TicketCase t=getForUpdate(id);requireStatus(t,TicketCaseStatus.OPEN,TicketCaseStatus.IN_PROGRESS);requireM2Thread(t);add(t,TicketUpdateType.INFORMATION_REQUEST,message);t.status=TicketCaseStatus.WAITING_FOR_INFORMATION;var request=json.createObjectNode();request.put("messageForCitizen",message);request.putNull("requiredBy");var details=json.createObjectNode();details.set("informationRequest",request);publish(t,"INFORMATION_REQUIRED",message,null,details,null);return t;}
    @Transactional TicketCase returnToM2(Long id,String reasonCode,String internalMessage){TicketCase t=getForUpdate(id);requireStatus(t,TicketCaseStatus.OPEN,TicketCaseStatus.IN_PROGRESS);requireM2Thread(t);CatalogService.require(RETURN_REASONS.contains(reasonCode),"INVALID_RETURN_REASON","Motivo de devolución no admitido por M2");CatalogService.require(internalMessage!=null&&!internalMessage.isBlank(),"INVALID_RETURN_REASON","La devolución requiere una explicación interna");add(t,TicketUpdateType.RETURNED,internalMessage);t.status=TicketCaseStatus.IN_REVIEW;t.assignedTo=null;t.completedAt=null;var info=json.createObjectNode();info.put("reasonCode",reasonCode);var details=json.createObjectNode();details.set("returnInfo",info);publish(t,"RETURNED",null,internalMessage,details,null);return t;}
    @Transactional TicketCase complete(Long id,String resolution){TicketCase t=getForUpdate(id);requireStatus(t,TicketCaseStatus.OPEN,TicketCaseStatus.IN_PROGRESS);requireM2Thread(t);add(t,TicketUpdateType.RESOLUTION,resolution);t.status=TicketCaseStatus.COMPLETED;t.completedAt=t.updatedAt;var value=json.createObjectNode();value.put("type","ACTION_COMPLETED");var details=json.createObjectNode();details.set("resolution",value);publish(t,"RESOLVED",resolution,null,details,null);return t;}
    @Transactional TicketCase reject(Long id,String reason){TicketCase t=getForUpdate(id);requireStatus(t,TicketCaseStatus.OPEN,TicketCaseStatus.IN_PROGRESS);requireM2Thread(t);add(t,TicketUpdateType.REJECTION,reason);t.status=TicketCaseStatus.REJECTED;t.completedAt=t.updatedAt;var cancellation=json.createObjectNode();cancellation.put("reasonCode","REJECTED_BY_AREA");var details=json.createObjectNode();details.set("cancellation",cancellation);publish(t,"REJECTED",null,reason,details,null);return t;}
    private void add(TicketCase t,TicketUpdateType type,String message){TicketCaseUpdate u=new TicketCaseUpdate();u.ticketCaseId=t.id;u.type=type;u.message=message;u.createdBy=identity.get().userId();u.createdAt=OffsetDateTime.now();updates.save(u);t.updatedAt=u.createdAt;audit.record("TicketCase",t.id,"TICKET_"+type.name(),u);}
    private void mutable(TicketCase t){requireStatus(t,TicketCaseStatus.OPEN,TicketCaseStatus.IN_PROGRESS);}
    private void requireStatus(TicketCase t,TicketCaseStatus... allowed){CatalogService.require(Arrays.asList(allowed).contains(t.status),"INVALID_TICKET_TRANSITION","El estado actual del ticket no admite esta operación");}
    private boolean isOfficialTicket(TicketCase t){try{UUID.fromString(t.externalTicketId);return true;}catch(IllegalArgumentException ex){return false;}}
    private void requireM2Thread(TicketCase t){if(isOfficialTicket(t))CatalogService.require(t.correlationId!=null&&!t.correlationId.isBlank(),"M2_CORRELATION_REQUIRED","Falta el correlationId original de M2; no se preparó el evento");}
    private String routedCause(TicketCase t){
        requireM2Thread(t);
        CatalogService.require(t.lastSourceEventId!=null,"M2_ROUTED_CAUSE_REQUIRED","No consta un ROUTED causal verificable");
        IntegrationEventLog source=integrationLogs.findFirstByExternalEventIdOrderByIdDesc(t.lastSourceEventId).orElseThrow(()->new BusinessException("M2_ROUTED_CAUSE_REQUIRED","No consta un ROUTED causal verificable",409));
        try{
            var payload=json.readTree(source.payload);
            CatalogService.require(source.direction==EventDirection.INBOUND&&source.status==IntegrationEventStatus.PROCESSED&&"ticketUpdated".equals(source.eventType)&&"atencion-ciudadana".equals(source.sourceModule)&&t.externalTicketId.equals(payload.path("data").path("ticketId").asText())&&"ROUTED".equals(payload.path("data").path("updateType").asText())&&t.correlationId.equals(payload.path("correlationId").asText()),"M2_ROUTED_CAUSE_REQUIRED","El evento causal no es el ROUTED vigente de M2");
            return source.externalEventId;
        }catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new BusinessException("M2_ROUTED_CAUSE_REQUIRED","No se puede verificar el ROUTED causal",409);}
    }
    private void publish(TicketCase t,String updateType,String publicMessage,String internalMessage,com.fasterxml.jackson.databind.JsonNode details,String causationId){
        // The pre-Core numeric ticket adapter remains local-only.
        if(!isOfficialTicket(t))return;
        requireM2Thread(t);
        events.ticketStatus(t.externalTicketId,t.id.toString(),updateType,publicMessage,internalMessage,details,identity.get().integrationActorId(),t.correlationId,causationId);
    }
}

@Service
class SocialBenefitIntegrationService {
    private final SocialBenefitRepository benefits;private final SocialBenefitTaxConceptRepository links;private final TaxpayerRepository taxpayers;private final TaxConceptRepository concepts;private final IntegrationInbox inbox;
    SocialBenefitIntegrationService(SocialBenefitRepository benefits,SocialBenefitTaxConceptRepository links,TaxpayerRepository taxpayers,TaxConceptRepository concepts,IntegrationInbox inbox){this.benefits=benefits;this.links=links;this.taxpayers=taxpayers;this.concepts=concepts;this.inbox=inbox;}
    @Transactional SocialBenefitReference consume(ApiDtos.EventEnvelope<ApiDtos.SocialBenefitEventData> event){CatalogService.require("socialBenefitUpdated".equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba socialBenefitUpdated");ApiDtos.SocialBenefitEventData d=event.data();Optional<SocialBenefitReference> existing=benefits.findByExternalBenefitId(d.externalBenefitId());if(inbox.processed(event.eventId()))return existing.orElseThrow();CatalogService.require(existing.isEmpty()||existing.get().externalApplicationId==null,"LEGACY_BENEFIT_CONFLICT","El adaptador legado no puede modificar un beneficio oficial de M8");CatalogService.require(d.validUntil()==null||!d.validUntil().isBefore(d.validFrom()),"INVALID_VALIDITY_RANGE","La vigencia del beneficio es inválida");List<TaxConcept> selected=d.taxConceptCodes()==null?List.of():d.taxConceptCodes().stream().distinct().map(code->concepts.findByCode(code).orElseThrow(()->CatalogService.notFound("Concepto "+code))).toList();IntegrationEventLog log=inbox.receive(event);SocialBenefitReference benefit=existing.orElseGet(SocialBenefitReference::new);if(benefit.id==null)benefit.externalBenefitId=d.externalBenefitId();benefit.externalCitizenId=d.externalCitizenId();benefit.taxpayerId=taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,d.externalCitizenId()).map(x->x.id).orElse(null);benefit.benefitType=d.benefitType();benefit.externalStatus=d.status().name();benefit.calculatedStatus=d.status();benefit.benefitsPayload="[]";benefit.discountPercentage=d.discountPercentage();benefit.validFrom=d.validFrom();benefit.validUntil=d.validUntil();benefit.sourceEventId=event.eventId();benefit.externalSourceEventId=event.eventId().toString();benefit.updatedAt=OffsetDateTime.now();benefit.receivedAt=benefit.updatedAt;benefit.resolutionStatus="LEGACY";benefit.sourcePayloadHash="0".repeat(64);benefits.save(benefit);links.deleteBySocialBenefitId(benefit.id);for(TaxConcept concept:selected){SocialBenefitTaxConcept link=new SocialBenefitTaxConcept();link.socialBenefitId=benefit.id;link.taxConceptId=concept.id;links.save(link);}inbox.complete(event,log);return benefit;}
}
