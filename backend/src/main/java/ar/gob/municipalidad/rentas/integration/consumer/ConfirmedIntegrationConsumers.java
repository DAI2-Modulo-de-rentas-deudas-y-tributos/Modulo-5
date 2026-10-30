package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDate;
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
        validator.validate(event);
        return consumeCitizen(event.data(),normalize(event.eventId(),event.eventType(),event.occurredAt(),event.producer(),event.subject(),event.data()),event);
    }
    @Transactional TaxpayerReference consumeCitizen(ConfirmedInboundEvents.M1CoreEnvelope<ConfirmedInboundEvents.M1CitizenUpdatedData> event){
        validateCoreM1(event);
        return consumeCitizen(event.data(),normalize(event),event);
    }
    private TaxpayerReference consumeCitizen(ConfirmedInboundEvents.M1CitizenUpdatedData data,NormalizedIntegrationEvent normalized,Object raw){
        CatalogService.require("citizenUpdated".equals(normalized.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba citizenUpdated");
        String externalId=data.citizenId().toString();Optional<TaxpayerReference> existing=taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,externalId);
        if(inbox.processed(normalized.eventId()))return existing.orElse(null);
        IntegrationEventLog log=inbox.receive(normalized,raw);
        TaxpayerReference taxpayer;
        switch(data.updateType()){
            case "REGISTERED"->{var details=json.convertValue(data.details(),ConfirmedInboundEvents.M1CitizenRegisteredDetails.class);taxpayer=existing.orElseGet(TaxpayerReference::new);if(taxpayer.id==null){taxpayer.taxpayerType=TaxpayerType.CITIZEN;taxpayer.externalId=externalId;taxpayer.createdAt=OffsetDateTime.now();}taxpayer.dni=details.dni();taxpayer.cuit=details.cuil();taxpayer.displayName=(details.name()+" "+details.lastname()).trim();taxpayer.externalStatus=TaxpayerStatus.ACTIVE;taxpayer.updatedAt=OffsetDateTime.now();taxpayers.save(taxpayer);}
            case "BLOCKED"->{json.convertValue(data.details(),ConfirmedInboundEvents.M1CitizenBlockedDetails.class);taxpayer=existing.orElseThrow(()->new BusinessException("TAXPAYER_REFERENCE_NOT_FOUND","No existe la referencia local del ciudadano",422));taxpayer.externalStatus=TaxpayerStatus.BLOCKED;taxpayer.updatedAt=OffsetDateTime.now();}
            case "DECEASED"->{json.convertValue(data.details(),ConfirmedInboundEvents.M1CitizenDeceasedDetails.class);taxpayer=existing.orElseThrow(()->new BusinessException("TAXPAYER_REFERENCE_NOT_FOUND","No existe la referencia local del ciudadano",422));taxpayer.externalStatus=TaxpayerStatus.DECEASED;taxpayer.updatedAt=OffsetDateTime.now();}
            case "ADDRESS_UPDATED"->{taxpayer=existing.orElseThrow(()->new BusinessException("TAXPAYER_REFERENCE_NOT_FOUND","No existe la referencia local del ciudadano",422));}
            default->throw new BusinessException("UNSUPPORTED_UPDATE_TYPE","Actualización de ciudadano no soportada",422);
        }
        inbox.complete(normalized,log);return taxpayer;
    }

    @Transactional TaxpayerReference consumeOrganization(ConfirmedInboundEvents.M1OrganizationRegisteredEvent event){
        validator.validate(event);
        return consumeOrganization(event.data(),normalize(event.eventId(),event.eventType(),event.occurredAt(),event.producer(),event.subject(),event.data()),event);
    }
    @Transactional TaxpayerReference consumeOrganization(ConfirmedInboundEvents.M1CoreEnvelope<ConfirmedInboundEvents.M1OrganizationRegisteredData> event){
        validateCoreM1(event);
        return consumeOrganization(event.data(),normalize(event),event);
    }
    private TaxpayerReference consumeOrganization(ConfirmedInboundEvents.M1OrganizationRegisteredData data,NormalizedIntegrationEvent normalized,Object raw){
        CatalogService.require("organizationRegistered".equals(normalized.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba organizationRegistered");
        String externalId=data.cuit().toString();Optional<TaxpayerReference> existing=taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.ORGANIZATION,externalId);if(inbox.processed(normalized.eventId()))return existing.orElse(null);
        IntegrationEventLog log=inbox.receive(normalized,raw);
        TaxpayerReference taxpayer=existing.orElseGet(TaxpayerReference::new);if(taxpayer.id==null){taxpayer.taxpayerType=TaxpayerType.ORGANIZATION;taxpayer.externalId=externalId;taxpayer.createdAt=OffsetDateTime.now();}taxpayer.cuit=data.taxId();taxpayer.displayName=data.legalName();taxpayer.externalStatus=mapTaxpayerStatus(data.status());taxpayer.updatedAt=OffsetDateTime.now();taxpayers.save(taxpayer);inbox.complete(normalized,log);return taxpayer;
    }

    @Transactional TaxpayerRepresentationReference consumeRepresentation(ConfirmedInboundEvents.M1RepresentationEvent event){
        validator.validate(event);
        return consumeRepresentation(event.data(),normalize(event.eventId(),event.eventType(),event.occurredAt(),event.producer(),event.subject(),event.data()),event);
    }
    @Transactional TaxpayerRepresentationReference consumeRepresentation(ConfirmedInboundEvents.M1CoreEnvelope<ConfirmedInboundEvents.M1RepresentationData> event){
        validateCoreM1(event);
        return consumeRepresentation(event.data(),normalize(event),event);
    }
    private TaxpayerRepresentationReference consumeRepresentation(ConfirmedInboundEvents.M1RepresentationData data,NormalizedIntegrationEvent normalized,Object raw){
        CatalogService.require(Set.of("representationGranted","representationExpired").contains(normalized.eventType()),"UNSUPPORTED_EVENT_TYPE","Evento de representación no soportado");
        String externalId=data.representationId().toString();Optional<TaxpayerRepresentationReference> existing=representations.findByExternalRepresentationId(externalId);if(inbox.processed(normalized.eventId()))return existing.orElse(null);
        IntegrationEventLog log=inbox.receive(normalized,raw);
        TaxpayerRepresentationReference reference=existing.orElseGet(TaxpayerRepresentationReference::new);reference.externalRepresentationId=externalId;reference.externalPersonId=data.personId().toString();reference.externalOrganizationId=data.cuit().toString();if(data.scope()!=null)reference.scope=data.scope();CatalogService.require(reference.scope!=null,"INVALID_EVENT","scope es obligatorio al otorgar una representación");if(data.from()!=null)reference.validFrom=data.from();reference.validUntil=data.until();reference.status=data.status();reference.sourceEventId=normalized.eventId();reference.updatedAt=OffsetDateTime.now();representations.save(reference);inbox.complete(normalized,log);return reference;
    }

    private NormalizedIntegrationEvent normalize(String id,String type,OffsetDateTime at,ConfirmedInboundEvents.Producer producer,String subject,Object data){return new NormalizedIntegrationEvent(id,type,at,producer.moduleId(),subject,json.valueToTree(data),null);}
    private NormalizedIntegrationEvent normalize(ConfirmedInboundEvents.M1CoreEnvelope<?> event){return new NormalizedIntegrationEvent(event.eventId().toString(),event.eventType(),event.occurredAt(),event.sourceModule(),null,json.valueToTree(event.data()),event.correlationId()==null?null:event.correlationId().toString());}
    private void validateCoreM1(ConfirmedInboundEvents.M1CoreEnvelope<?> event){
        validator.validate(event);
        CatalogService.require("1.0".equals(event.eventVersion()),"UNSUPPORTED_EVENT_VERSION","Evento de M1 requiere eventVersion 1.0");
        CatalogService.require("ciudadanos".equals(event.sourceModule()),"INVALID_SOURCE_MODULE","Evento de M1 debe provenir de ciudadanos");
    }
    private TaxpayerStatus mapTaxpayerStatus(String status){try{return TaxpayerStatus.valueOf(status);}catch(IllegalArgumentException ex){throw new BusinessException("INVALID_EVENT","Estado de organización desconocido",422);}}
}

@Service
class ConfirmedM2Consumer {
    private static final Set<String> UPDATE_TYPES=Set.of("STATUS_CHANGED","ROUTED","PROGRESS","PRIORITY_CHANGED",
        "ESCALATION_CHANGED","INFORMATION_REQUIRED","INFORMATION_PROVIDED","DUPLICATE_LINKED","REOPENED",
        "RESOLVED","CLOSED","CANCELLED","CONTENT_UPDATED");
    private final TicketCaseRepository tickets;private final TicketCaseUpdateRepository updates;private final TaxpayerRepository taxpayers;
    private final IntegrationInbox inbox;private final ConfirmedContractValidator validator;private final ObjectMapper json;
    ConfirmedM2Consumer(TicketCaseRepository tickets,TicketCaseUpdateRepository updates,TaxpayerRepository taxpayers,IntegrationInbox inbox,ConfirmedContractValidator validator,ObjectMapper json){this.tickets=tickets;this.updates=updates;this.taxpayers=taxpayers;this.inbox=inbox;this.validator=validator;this.json=json;}

    @Transactional TicketCase consume(ConfirmedInboundEvents.M2TicketUpdatedEvent event){
        validator.validate(event);
        CatalogService.require(event.eventId().version()==4,"INVALID_EVENT_ID","eventId debe ser UUIDv4");
        CatalogService.require("ticketUpdated".equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba ticketUpdated");
        CatalogService.require("1.0".equals(event.eventVersion()),"UNSUPPORTED_EVENT_VERSION","ticketUpdated sólo admite eventVersion 1.0");
        CatalogService.require("atencion-ciudadana".equals(event.sourceModule()),"INVALID_SOURCE_MODULE","ticketUpdated debe provenir de atención ciudadana");
        var data=event.data();CatalogService.require(UPDATE_TYPES.contains(data.updateType()),"UNSUPPORTED_UPDATE_TYPE","updateType de M2 no soportado");
        String ticketId=data.ticketId().toString();
        Optional<TicketCase> existing=tickets.findByExternalTicketIdForUpdate(ticketId);
        if(inbox.processed(event.eventId()))return existing.orElse(null);
        var normalized=new NormalizedIntegrationEvent(event.eventId().toString(),event.eventType(),event.occurredAt(),event.sourceModule(),ticketId,json.valueToTree(data),event.correlationId()==null?null:event.correlationId().toString());
        if(!"M5".equals(data.responsibleAreaId())||isAdministrativeReclassification(data)){inbox.ignore(normalized);return null;}
        if(existing.isEmpty()&&Set.of("REGISTERED","IN_REVIEW","DUPLICATE").contains(data.currentStatus())){inbox.ignore(normalized);return null;}
        if("ROUTED".equals(data.updateType()))validateRouting(data.details());
        IntegrationEventLog log=inbox.receive(normalized,event);
        TicketCase ticket=existing.orElseGet(TicketCase::new);
        if(ticket.id==null){
            CatalogService.require("ROUTED".equals(data.updateType()),"TICKET_SNAPSHOT_REQUIRED","El primer evento del ticket debe ser ROUTED");
            ticket.externalTicketId=ticketId;ticket.createdAt=event.occurredAt();
        }
        if(ticket.lastExternalUpdatedAt!=null&&!data.updatedAt().isAfter(ticket.lastExternalUpdatedAt)){
            inbox.complete(normalized,log);return ticket;
        }
        if(ticket.id==null)ticket.correlationId=event.correlationId()==null?null:event.correlationId().toString();
        else CatalogService.require(event.correlationId()==null||event.correlationId().toString().equals(ticket.correlationId),
            "M2_CORRELATION_CONFLICT","El evento de M2 no pertenece al hilo ROUTED original");
        TicketCaseStatus next=nextStatus(ticket,data);
        CatalogService.require(data.isAnonymous()?data.citizenId()==null:data.citizenId()!=null,
            "INVALID_EVENT","citizenId debe estar presente sólo en tickets identificados");
        ticket.publicId=data.publicId();
        // Existing column now denotes the applicable ROUTED event, never an unrelated informational update.
        if("ROUTED".equals(data.updateType()))ticket.lastSourceEventId=event.eventId().toString();
        ticket.externalCitizenId=data.isAnonymous()?null:data.citizenId().toString();
        ticket.taxpayerId=ticket.externalCitizenId==null?null:taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,ticket.externalCitizenId).map(x->x.id).orElse(null);
        if("ROUTED".equals(data.updateType())){
            JsonNode routing=data.details().path("routing");ticket.requestType=text(routing,"requestType");
            ticket.category=text(routing,"category");ticket.subcategory=text(routing,"subcategory");
            ticket.description=text(routing,"description");
            if(routing.path("reassignment").asBoolean(false))ticket.assignedTo=null;
        }
        if(ticket.category==null)ticket.category="M2";
        if(ticket.description==null)ticket.description=data.publicMessage()==null?"Ticket derivado a Rentas":data.publicMessage();
        if(!"ESCALATION_CHANGED".equals(data.updateType()))ticket.priority=priority(data.currentPriority());
        ticket.status=next;ticket.updatedAt=data.updatedAt();ticket.lastExternalUpdatedAt=data.updatedAt();
        ticket.completedAt=ticket.status==TicketCaseStatus.COMPLETED||ticket.status==TicketCaseStatus.REJECTED||
            ticket.status==TicketCaseStatus.CANCELLED?data.updatedAt():null;
        tickets.save(ticket);
        if("INFORMATION_PROVIDED".equals(data.updateType())){
            String response=text(data.details(),"informationResponse","message");
            CatalogService.require((response!=null&&!response.isBlank())||(data.attachments()!=null&&!data.attachments().isEmpty()),
                "INVALID_EVENT","INFORMATION_PROVIDED requiere mensaje o adjunto");
            TicketCaseUpdate update=new TicketCaseUpdate();update.ticketCaseId=ticket.id;update.type=TicketUpdateType.INTERNAL_NOTE;
            update.message=response==null?"Se recibieron adjuntos nuevos":response;update.createdBy="M2";
            update.createdAt=data.updatedAt();updates.save(update);
        }
        inbox.complete(normalized,log);return ticket;
    }

    private TicketCaseStatus nextStatus(TicketCase ticket,ConfirmedInboundEvents.M2TicketUpdatedData data){
        String type=data.updateType(),current=data.currentStatus();TicketCaseStatus previous=ticket.status;
        switch(type){
            case "ROUTED" -> {
                CatalogService.require("ROUTED".equals(current),"INVALID_M2_TRANSITION","ROUTED requiere currentStatus ROUTED");
                boolean reassignment=data.details().path("routing").path("reassignment").asBoolean(false);
                CatalogService.require(previous==null||(!terminal(previous)&&
                    (reassignment||previous==TicketCaseStatus.OPEN&&ticket.assignedTo==null)),
                    "INVALID_M2_TRANSITION","Un ROUTED posterior requiere una reasignación real");
                return TicketCaseStatus.OPEN;
            }
            case "INFORMATION_PROVIDED" -> {
                CatalogService.require(Set.of("ROUTED","IN_PROGRESS").contains(current)&&
                    (previous==TicketCaseStatus.OPEN||previous==TicketCaseStatus.IN_PROGRESS||previous==TicketCaseStatus.WAITING_FOR_INFORMATION),
                    "INVALID_M2_TRANSITION","INFORMATION_PROVIDED no puede reabrir un ticket terminal");
                return status(current);
            }
            case "INFORMATION_REQUIRED" -> {
                CatalogService.require("PENDING_INFORMATION".equals(current)&&
                    (previous==TicketCaseStatus.OPEN||previous==TicketCaseStatus.IN_PROGRESS),
                    "INVALID_M2_TRANSITION","INFORMATION_REQUIRED requiere una gestión activa");
                return TicketCaseStatus.WAITING_FOR_INFORMATION;
            }
            case "REOPENED" -> {
                CatalogService.require(previous==TicketCaseStatus.COMPLETED&&"IN_PROGRESS".equals(current),
                    "INVALID_M2_TRANSITION","REOPENED requiere un ticket resuelto y vuelve a IN_PROGRESS");
                return TicketCaseStatus.IN_PROGRESS;
            }
            case "CANCELLED" -> {
                CatalogService.require("CANCELLED".equals(current)&&previous!=null&&!terminal(previous)&&
                    text(data.details(),"cancellation","reasonCode")!=null,
                    "INVALID_M2_TRANSITION","CANCELLED requiere un motivo y un ticket no terminal");
                return TicketCaseStatus.CANCELLED;
            }
            case "PRIORITY_CHANGED","ESCALATION_CHANGED","STATUS_CHANGED","CONTENT_UPDATED" -> {
                return previous;
            }
            case "PROGRESS" -> {
                CatalogService.require(previous==TicketCaseStatus.IN_PROGRESS&&"IN_PROGRESS".equals(current),
                    "INVALID_M2_TRANSITION","PROGRESS requiere IN_PROGRESS");
                return previous;
            }
            case "DUPLICATE_LINKED" -> {
                CatalogService.require("DUPLICATE".equals(current)&&previous!=null&&!terminal(previous),
                    "INVALID_M2_TRANSITION","DUPLICATE_LINKED requiere un ticket activo");
                return TicketCaseStatus.DUPLICATE;
            }
            case "RESOLVED","CLOSED" -> {
                CatalogService.require(("RESOLVED".equals(type)&&"RESOLVED".equals(current))||
                    ("CLOSED".equals(type)&&"CLOSED".equals(current)),"INVALID_M2_TRANSITION","Estado final de M2 inconsistente");
                CatalogService.require(previous!=null&&(!terminal(previous)||previous==TicketCaseStatus.COMPLETED),
                    "INVALID_M2_TRANSITION","Un ticket cancelado, rechazado o duplicado no puede resolverse");
                return TicketCaseStatus.COMPLETED;
            }
            default -> throw new BusinessException("UNSUPPORTED_UPDATE_TYPE","updateType de M2 no soportado",422);
        }
    }
    private boolean terminal(TicketCaseStatus value){return value==TicketCaseStatus.COMPLETED||value==TicketCaseStatus.REJECTED||value==TicketCaseStatus.CANCELLED||value==TicketCaseStatus.DUPLICATE;}
    private TicketPriority priority(String value){try{return TicketPriority.valueOf(value);}catch(IllegalArgumentException ex){throw new BusinessException("INVALID_EVENT","Prioridad de ticket desconocida",422);}}
    private TicketCaseStatus status(String value){return switch(value){case "ROUTED"->TicketCaseStatus.OPEN;case "REGISTERED","IN_REVIEW"->TicketCaseStatus.IN_REVIEW;case "DUPLICATE"->TicketCaseStatus.DUPLICATE;case "IN_PROGRESS"->TicketCaseStatus.IN_PROGRESS;case "PENDING_INFORMATION"->TicketCaseStatus.WAITING_FOR_INFORMATION;case "RESOLVED","CLOSED"->TicketCaseStatus.COMPLETED;case "CANCELLED"->TicketCaseStatus.CANCELLED;default->throw new BusinessException("INVALID_EVENT","Estado de ticket desconocido",422);};}
    private boolean isAdministrativeReclassification(ConfirmedInboundEvents.M2TicketUpdatedData data){
        if(!Set.of("INFORMATION_REQUIRED","INFORMATION_PROVIDED").contains(data.updateType())||data.details()==null)return false;
        String branch="INFORMATION_REQUIRED".equals(data.updateType())?"informationRequest":"informationResponse";
        return "RECLASSIFICATION".equals(text(data.details(),branch,"purpose"));
    }
    private void validateRouting(JsonNode details){
        JsonNode routing=details==null?null:details.path("routing");
        CatalogService.require(routing!=null&&routing.isObject(),"INVALID_EVENT","ROUTED requiere details.routing");
        for(String field:List.of("requestType","category","subcategory","ticketType","summary","description"))
            CatalogService.require(text(routing,field)!=null&&!text(routing,field).isBlank(),"INVALID_EVENT","ROUTED requiere details.routing."+field);
        JsonNode reassignment=routing.path("reassignment");
        CatalogService.require(reassignment.isMissingNode()||reassignment.isBoolean(),"INVALID_EVENT","routing.reassignment debe ser booleano");
    }
    private String text(JsonNode node,String... path){if(node==null)return null;JsonNode current=node;for(String part:path)current=current.path(part);return current.isMissingNode()||current.isNull()?null:current.asText();}
}

@Service
class ConfirmedM8Consumer {
    private static final Set<String> STATUSES=Set.of("APPROVED","SUSPENDED","FINALIZED");
    private final SocialBenefitRepository benefits;private final SocialBenefitTaxConceptRepository links;private final TaxpayerRepository taxpayers;
    private final SocialBenefitRuleRepository rules;private final SocialBenefitEventHistoryRepository history;
    private final IntegrationInbox inbox;private final ConfirmedContractValidator validator;private final ObjectMapper json;
    ConfirmedM8Consumer(SocialBenefitRepository benefits,SocialBenefitTaxConceptRepository links,TaxpayerRepository taxpayers,SocialBenefitRuleRepository rules,SocialBenefitEventHistoryRepository history,IntegrationInbox inbox,ConfirmedContractValidator validator,ObjectMapper json){this.benefits=benefits;this.links=links;this.taxpayers=taxpayers;this.rules=rules;this.history=history;this.inbox=inbox;this.validator=validator;this.json=json;}

    @Transactional SocialBenefitReference consume(ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent event){
        validator.validate(event);
        CatalogService.require("socialBenefitUpdated".equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba socialBenefitUpdated");
        CatalogService.require("1.0".equals(event.eventVersion()),"UNSUPPORTED_EVENT_VERSION","Versión de M8 no soportada");
        CatalogService.require("desarrollo-social".equals(event.sourceModule()),"INVALID_SOURCE_MODULE","El beneficio debe provenir de M8");
        var data=event.data();CatalogService.require(STATUSES.contains(data.status()),"INVALID_EVENT","Estado de beneficio social desconocido");
        CatalogService.require(data.startDate()==null||data.endDate()==null||!data.endDate().isBefore(data.startDate()),"INVALID_VALIDITY_RANGE","La vigencia del beneficio es inválida");
        CatalogService.require(!"APPROVED".equals(data.status())||data.startDate()!=null,"INVALID_VALIDITY_RANGE","APPROVED requiere fecha de inicio");
        CatalogService.require("APPROVED".equals(data.status())||(data.reason()!=null&&!data.reason().isBlank()),"INVALID_EVENT","La suspensión o finalización requiere motivo");
        CatalogService.require(!"FINALIZED".equals(data.status())||data.endDate()!=null,"INVALID_VALIDITY_RANGE","FINALIZED requiere fecha efectiva de fin");
        String benefitId=data.benefitId().toString(), citizenId=data.citizenId().toString(), payloadHash=hash(data);
        Optional<SocialBenefitReference> existing=benefits.findByExternalBenefitIdForUpdate(benefitId);
        if(inbox.processed(event.eventId().toString()))return existing.orElse(null);
        existing.ifPresent(current->{
            CatalogService.require(current.externalCitizenId.equals(citizenId)&&
                Objects.equals(current.externalApplicationId,data.applicationId().toString())&&
                Objects.equals(current.externalProgramId,data.programId().toString()),
                "BENEFIT_IDENTITY_CONFLICT","El beneficio no puede cambiar de ciudadano, solicitud o programa");
        });
        var normalized=new NormalizedIntegrationEvent(event.eventId().toString(),event.eventType(),event.occurredAt(),event.sourceModule(),null,json.valueToTree(data),null);
        IntegrationEventLog log=inbox.receive(normalized,event);
        SocialBenefitReference benefit=existing.orElseGet(SocialBenefitReference::new);
        String outcome="APPLIED";
        if(existing.isPresent()&&data.updatedAt().isBefore(benefit.updatedAt))outcome="STALE";
        else if(existing.isPresent()&&data.updatedAt().isEqual(benefit.updatedAt))
            outcome=Objects.equals(benefit.sourcePayloadHash,payloadHash)?"SAME_STATE":"CONFLICT";
        if("APPLIED".equals(outcome)){
            LocalDate effectiveFrom="APPROVED".equals(data.status())||data.startDate()!=null?data.startDate():benefit.validFrom;
            LocalDate effectiveUntil="APPROVED".equals(data.status())||data.endDate()!=null?data.endDate():benefit.validUntil;
            CatalogService.require(effectiveFrom==null||effectiveUntil==null||!effectiveUntil.isBefore(effectiveFrom),
                "INVALID_VALIDITY_RANGE","La vigencia del beneficio es inválida");
            benefit.externalBenefitId=benefitId;benefit.externalCitizenId=citizenId;
            benefit.taxpayerId=taxpayers.findByTaxpayerTypeAndCanonicalCitizenId(TaxpayerType.CITIZEN,data.citizenId()).map(x->x.id).orElse(null);
            benefit.externalApplicationId=data.applicationId().toString();benefit.externalProgramId=data.programId().toString();benefit.programName=data.programName();
            benefit.benefitType=data.type();
            benefit.benefitsPayload=write(data);
            benefit.externalStatus=data.status();benefit.externalReason=data.reason();
            benefit.calculatedStatus=calculatedStatus(data.status());benefit.discountPercentage=null;
            benefit.validFrom=effectiveFrom;benefit.validUntil=effectiveUntil;
            benefit.sourceEventId=event.eventId();benefit.externalSourceEventId=event.eventId().toString();
            benefit.lastCorrelationId=event.correlationId();benefit.sourcePayloadHash=payloadHash;
            benefit.updatedAt=data.updatedAt();benefit.receivedAt=OffsetDateTime.now();
            benefit.resolutionStatus=resolution(benefit,data.programId());
            benefits.save(benefit);links.deleteBySocialBenefitId(benefit.id);
        }
        SocialBenefitEventHistory entry=new SocialBenefitEventHistory();entry.sourceEventId=event.eventId();entry.externalBenefitId=benefitId;
        entry.sourceUpdatedAt=data.updatedAt();entry.externalStatus=data.status();entry.outcome=outcome;entry.payloadHash=payloadHash;
        entry.recordedAt=OffsetDateTime.now();history.save(entry);
        inbox.complete(normalized,log);return benefit;
    }
    private String resolution(SocialBenefitReference benefit,UUID programId){
        if(!"TAX_EXEMPTION".equals(benefit.benefitType))return "NOT_APPLICABLE";
        if(benefit.taxpayerId==null)return "PENDING_CITIZEN";
        if(benefit.validFrom==null)return "PENDING_VALIDITY";
        return rules.findByProgramId(programId).stream().anyMatch(r->r.status==SocialBenefitRuleStatus.APPROVED&&r.enabled&&
            (r.validUntil==null||!r.validUntil.isBefore(benefit.validFrom))&&
            (benefit.validUntil==null||!r.validFrom.isAfter(benefit.validUntil)))?"RESOLVED":"PENDING_TAX_RULE";
    }
    private String hash(Object data){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(data));return HexFormat.of().formatHex(digest);}catch(Exception ex){throw new BusinessException("INVALID_EVENT_PAYLOAD","No se pudo resumir el beneficio",422);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new BusinessException("INVALID_EVENT_PAYLOAD","No se pudo serializar benefits",422);}}
    private SocialBenefitStatus calculatedStatus(String external){return switch(external){case "APPROVED"->SocialBenefitStatus.ACTIVE;case "SUSPENDED"->SocialBenefitStatus.SUSPENDED;case "FINALIZED"->SocialBenefitStatus.EXPIRED;default->throw new BusinessException("INVALID_EVENT","Estado de beneficio social desconocido",422);};}
}

@Service
class ConfirmedM7Consumer {
    private final ExternalObligationService obligations;private final ConfirmedContractValidator validator;
    ConfirmedM7Consumer(ExternalObligationService obligations,ConfirmedContractValidator validator){this.obligations=obligations;this.validator=validator;}

    @Transactional ExternalObligation consume(ConfirmedInboundEvents.M7InfractionConfirmedEvent event){
        validator.validate(event);
        CatalogService.require("infractionConfirmed".equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba infractionConfirmed");
        CatalogService.require("1.0".equals(event.eventVersion()),"UNSUPPORTED_EVENT_VERSION","infractionConfirmed sólo admite eventVersion 1.0");
        CatalogService.require("transito".equals(event.sourceModule()),"INVALID_SOURCE_MODULE","infractionConfirmed sólo admite sourceModule transito");
        return obligations.consumeConfirmedInfraction(event);
    }
}
