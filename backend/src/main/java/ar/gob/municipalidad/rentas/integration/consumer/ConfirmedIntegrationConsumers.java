package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
class ConfirmedM1Consumer {
    private final TaxpayerRepository taxpayers;
    private final TaxpayerRepresentationRepository representations;
    private final IntegrationInbox inbox;
    private final ConfirmedContractValidator validator;
    private final ObjectMapper json;

    ConfirmedM1Consumer(TaxpayerRepository taxpayers,TaxpayerRepresentationRepository representations,
        IntegrationInbox inbox,ConfirmedContractValidator validator,ObjectMapper json){this.taxpayers=taxpayers;this.representations=representations;this.inbox=inbox;this.validator=validator;this.json=json;}

    @Transactional TaxpayerReference consumeCitizen(ConfirmedInboundEvents.M1CitizenUpdatedEvent event){
        validator.validate(event);CatalogService.require("citizenUpdated".equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba citizenUpdated");
        var data=event.data();String externalId=data.citizenId().toString();Optional<TaxpayerReference> existing=taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,externalId);
        if(inbox.processed(event.eventId()))return existing.orElse(null);
        IntegrationEventLog log=inbox.receive(normalize(event.eventId(),event.eventType(),event.occurredAt(),event.producer(),event.subject(),data),event);
        TaxpayerReference taxpayer;
        switch(data.updateType()){
            case "REGISTERED"->{var details=json.convertValue(data.details(),ConfirmedInboundEvents.M1CitizenRegisteredDetails.class);taxpayer=existing.orElseGet(TaxpayerReference::new);if(taxpayer.id==null){taxpayer.taxpayerType=TaxpayerType.CITIZEN;taxpayer.externalId=externalId;taxpayer.createdAt=OffsetDateTime.now();}taxpayer.dni=details.dni();taxpayer.cuit=details.cuil();taxpayer.displayName=(details.name()+" "+details.lastname()).trim();taxpayer.externalStatus=TaxpayerStatus.ACTIVE;taxpayer.updatedAt=OffsetDateTime.now();taxpayers.save(taxpayer);}
            case "BLOCKED"->{json.convertValue(data.details(),ConfirmedInboundEvents.M1CitizenBlockedDetails.class);taxpayer=existing.orElseThrow(()->new BusinessException("TAXPAYER_REFERENCE_NOT_FOUND","No existe la referencia local del ciudadano",422));taxpayer.externalStatus=TaxpayerStatus.BLOCKED;taxpayer.updatedAt=OffsetDateTime.now();}
            case "DECEASED"->{json.convertValue(data.details(),ConfirmedInboundEvents.M1CitizenDeceasedDetails.class);taxpayer=existing.orElseThrow(()->new BusinessException("TAXPAYER_REFERENCE_NOT_FOUND","No existe la referencia local del ciudadano",422));taxpayer.externalStatus=TaxpayerStatus.DECEASED;taxpayer.updatedAt=OffsetDateTime.now();}
            case "ADDRESS_UPDATED"->{taxpayer=existing.orElseThrow(()->new BusinessException("TAXPAYER_REFERENCE_NOT_FOUND","No existe la referencia local del ciudadano",422));}
            default->throw new BusinessException("UNSUPPORTED_UPDATE_TYPE","Actualización de ciudadano no soportada",422);
        }
        inbox.complete(normalize(event.eventId(),event.eventType(),event.occurredAt(),event.producer(),event.subject(),data),log);return taxpayer;
    }

    @Transactional TaxpayerReference consumeOrganization(ConfirmedInboundEvents.M1OrganizationRegisteredEvent event){
        validator.validate(event);CatalogService.require("organizationRegistered".equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba organizationRegistered");
        var data=event.data();String externalId=data.cuit().toString();Optional<TaxpayerReference> existing=taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.ORGANIZATION,externalId);if(inbox.processed(event.eventId()))return existing.orElse(null);
        var normalized=normalize(event.eventId(),event.eventType(),event.occurredAt(),event.producer(),event.subject(),data);IntegrationEventLog log=inbox.receive(normalized,event);
        TaxpayerReference taxpayer=existing.orElseGet(TaxpayerReference::new);if(taxpayer.id==null){taxpayer.taxpayerType=TaxpayerType.ORGANIZATION;taxpayer.externalId=externalId;taxpayer.createdAt=OffsetDateTime.now();}taxpayer.cuit=data.taxId();taxpayer.displayName=data.legalName();taxpayer.externalStatus=mapTaxpayerStatus(data.status());taxpayer.updatedAt=OffsetDateTime.now();taxpayers.save(taxpayer);inbox.complete(normalized,log);return taxpayer;
    }

    @Transactional TaxpayerRepresentationReference consumeRepresentation(ConfirmedInboundEvents.M1RepresentationEvent event){
        validator.validate(event);CatalogService.require(Set.of("representationGranted","representationExpired").contains(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Evento de representación no soportado");
        var data=event.data();String externalId=data.representationId().toString();Optional<TaxpayerRepresentationReference> existing=representations.findByExternalRepresentationId(externalId);if(inbox.processed(event.eventId()))return existing.orElse(null);
        var normalized=normalize(event.eventId(),event.eventType(),event.occurredAt(),event.producer(),event.subject(),data);IntegrationEventLog log=inbox.receive(normalized,event);
        TaxpayerRepresentationReference reference=existing.orElseGet(TaxpayerRepresentationReference::new);reference.externalRepresentationId=externalId;reference.externalPersonId=data.personId().toString();reference.externalOrganizationId=data.cuit().toString();if(data.scope()!=null)reference.scope=data.scope();CatalogService.require(reference.scope!=null,"INVALID_EVENT","scope es obligatorio al otorgar una representación");if(data.from()!=null)reference.validFrom=data.from();reference.validUntil=data.until();reference.status=data.status();reference.sourceEventId=event.eventId();reference.updatedAt=OffsetDateTime.now();representations.save(reference);inbox.complete(normalized,log);return reference;
    }

    private NormalizedIntegrationEvent normalize(String id,String type,OffsetDateTime at,ConfirmedInboundEvents.Producer producer,String subject,Object data){return new NormalizedIntegrationEvent(id,type,at,producer.moduleId(),subject,json.valueToTree(data),null);}
    private TaxpayerStatus mapTaxpayerStatus(String status){try{return TaxpayerStatus.valueOf(status);}catch(IllegalArgumentException ex){throw new BusinessException("INVALID_EVENT","Estado de organización desconocido",422);}}
}

@Service
class ConfirmedM2Consumer {
    private final TicketCaseRepository tickets;private final TicketCaseUpdateRepository updates;private final TaxpayerRepository taxpayers;
    private final IntegrationInbox inbox;private final ConfirmedContractValidator validator;private final ObjectMapper json;
    ConfirmedM2Consumer(TicketCaseRepository tickets,TicketCaseUpdateRepository updates,TaxpayerRepository taxpayers,IntegrationInbox inbox,ConfirmedContractValidator validator,ObjectMapper json){this.tickets=tickets;this.updates=updates;this.taxpayers=taxpayers;this.inbox=inbox;this.validator=validator;this.json=json;}

    @Transactional TicketCase consume(ConfirmedInboundEvents.M2TicketUpdatedEvent event){
        validator.validate(event);CatalogService.require("ticketUpdated".equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba ticketUpdated");var data=event.data();String ticketId=data.ticketId().toString();Optional<TicketCase> existing=tickets.findByExternalTicketId(ticketId);if(inbox.processed(event.eventId()))return existing.orElse(null);
        var normalized=new NormalizedIntegrationEvent(event.eventId(),event.eventType(),event.occurredAt(),event.producer().moduleId(),event.subject(),json.valueToTree(data),null);
        if(!"M5".equals(data.responsibleAreaId())){inbox.ignore(normalized);return null;}
        IntegrationEventLog log=inbox.receive(normalized,event);TicketCase ticket=existing.orElseGet(TicketCase::new);
        if(ticket.id==null){CatalogService.require("ROUTED".equals(data.updateType()),"TICKET_SNAPSHOT_REQUIRED","El primer evento del ticket debe ser ROUTED");ticket.externalTicketId=ticketId;ticket.createdAt=event.occurredAt();}
        ticket.externalCitizenId=data.isAnonymous()||data.citizenId()==null?null:data.citizenId().toString();ticket.taxpayerId=ticket.externalCitizenId==null?null:taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,ticket.externalCitizenId).map(x->x.id).orElse(null);
        if("ROUTED".equals(data.updateType())){JsonNode routing=data.details()==null?null:data.details().path("routing");ticket.category=text(routing,"requestType","name");if(ticket.category==null)ticket.category=text(routing,"ticketType");ticket.description=text(routing,"description");}
        if(ticket.category==null)ticket.category="M2";if(ticket.description==null)ticket.description=data.publicMessage()==null?"Ticket derivado a Rentas":data.publicMessage();
        ticket.priority=priority(data.currentPriority());ticket.status=status(data.currentStatus());ticket.updatedAt=data.updatedAt();if(ticket.status==TicketCaseStatus.COMPLETED||ticket.status==TicketCaseStatus.REJECTED)ticket.completedAt=data.updatedAt();else ticket.completedAt=null;tickets.save(ticket);
        if("INFORMATION_PROVIDED".equals(data.updateType())&&data.publicMessage()!=null){TicketCaseUpdate update=new TicketCaseUpdate();update.ticketCaseId=ticket.id;update.type=TicketUpdateType.INTERNAL_NOTE;update.message=data.publicMessage();update.createdBy="M2";update.createdAt=data.updatedAt();updates.save(update);}
        inbox.complete(normalized,log);return ticket;
    }

    private TicketPriority priority(String value){try{return TicketPriority.valueOf(value);}catch(IllegalArgumentException ex){throw new BusinessException("INVALID_EVENT","Prioridad de ticket desconocida",422);}}
    private TicketCaseStatus status(String value){return switch(value){case "ROUTED","OPEN"->TicketCaseStatus.OPEN;case "IN_PROGRESS"->TicketCaseStatus.IN_PROGRESS;case "PENDING_INFORMATION"->TicketCaseStatus.WAITING_FOR_INFORMATION;case "RESOLVED"->TicketCaseStatus.COMPLETED;case "CANCELLED"->TicketCaseStatus.REJECTED;default->throw new BusinessException("INVALID_EVENT","Estado de ticket desconocido",422);};}
    private String text(JsonNode node,String... path){if(node==null)return null;JsonNode current=node;for(String part:path)current=current.path(part);return current.isMissingNode()||current.isNull()?null:current.asText();}
}

@Service
class ConfirmedM8Consumer {
    private static final Set<String> STATUSES=Set.of("APPROVED","REJECTED","SUSPENDED","FINALIZED");
    private final SocialBenefitRepository benefits;private final SocialBenefitTaxConceptRepository links;private final TaxpayerRepository taxpayers;
    private final IntegrationInbox inbox;private final ConfirmedContractValidator validator;private final ObjectMapper json;
    ConfirmedM8Consumer(SocialBenefitRepository benefits,SocialBenefitTaxConceptRepository links,TaxpayerRepository taxpayers,IntegrationInbox inbox,ConfirmedContractValidator validator,ObjectMapper json){this.benefits=benefits;this.links=links;this.taxpayers=taxpayers;this.inbox=inbox;this.validator=validator;this.json=json;}

    @Transactional SocialBenefitReference consume(ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent event){
        validator.validate(event);CatalogService.require("socialBenefitUpdated".equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba socialBenefitUpdated");var data=event.payload();CatalogService.require(STATUSES.contains(data.status()),"INVALID_EVENT","Estado de beneficio social desconocido");CatalogService.require(data.endDate()==null||!data.endDate().isBefore(data.startDate()),"INVALID_VALIDITY_RANGE","La vigencia del beneficio es inválida");Optional<SocialBenefitReference> existing=benefits.findByExternalBenefitId(data.benefitId());if(inbox.processed(event.eventId()))return existing.orElse(null);
        var normalized=new NormalizedIntegrationEvent(event.eventId(),event.eventType(),event.timestamp(),event.sourceModule(),null,json.valueToTree(data),null);IntegrationEventLog log=inbox.receive(normalized,event);SocialBenefitReference benefit=existing.orElseGet(SocialBenefitReference::new);benefit.externalBenefitId=data.benefitId();benefit.externalCitizenId=data.citizenId();benefit.taxpayerId=taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,data.citizenId()).map(x->x.id).orElse(null);benefit.externalApplicationId=data.applicationId();benefit.externalProgramId=data.programId();benefit.programName=data.programName();benefit.benefitType=data.benefits().stream().anyMatch(x->"TAX_EXEMPTION".equals(x.type()))?"TAX_EXEMPTION":"SOCIAL_BENEFIT";benefit.benefitsPayload=write(data.benefits());benefit.externalStatus=data.status();benefit.calculatedStatus=calculatedStatus(data.status());benefit.discountPercentage=null;benefit.validFrom=data.startDate();benefit.validUntil=data.endDate();benefit.sourceEventId=IntegrationInbox.technicalId(event.eventId());benefit.externalSourceEventId=event.eventId();benefit.updatedAt=data.updatedAt();benefits.save(benefit);links.deleteBySocialBenefitId(benefit.id);inbox.complete(normalized,log);return benefit;
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new BusinessException("INVALID_EVENT_PAYLOAD","No se pudo serializar benefits",422);}}
    private SocialBenefitStatus calculatedStatus(String external){return switch(external){case "APPROVED"->SocialBenefitStatus.ACTIVE;case "SUSPENDED"->SocialBenefitStatus.SUSPENDED;case "FINALIZED"->SocialBenefitStatus.EXPIRED;case "REJECTED"->SocialBenefitStatus.CANCELLED;default->throw new BusinessException("INVALID_EVENT","Estado de beneficio social desconocido",422);};}
}

@Service
class ConfirmedM7Consumer {
    private final ExternalObligationService obligations;private final ConfirmedContractValidator validator;
    ConfirmedM7Consumer(ExternalObligationService obligations,ConfirmedContractValidator validator){this.obligations=obligations;this.validator=validator;}

    @Transactional ExternalObligation consume(ConfirmedInboundEvents.M7InfractionConfirmedEvent event){
        validator.validate(event);
        CatalogService.require("infractionConfirmed".equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba infractionConfirmed");
        CatalogService.require("transito".equals(event.sourceModule()),"INVALID_SOURCE_MODULE","infractionConfirmed sólo admite sourceModule transito");
        return obligations.consumeConfirmedInfraction(event);
    }
}
