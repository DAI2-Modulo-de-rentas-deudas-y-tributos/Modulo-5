package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
class CoreM4Consumer {
    static final String DUE_DATE_PENDING="M4_DUE_DATE_POLICY_PENDING";
    static final String TAXPAYER_PENDING="M4_TAXPAYER_NOT_FOUND";
    static final String SOURCE_ID_PENDING="M4_SOURCE_ID_REVIEW_REQUIRED";
    private final IntegrationEventLogRepository logs;
    private final TaxpayerRepository taxpayers;
    private final TaxConceptRepository concepts;
    private final ExternalObligationService obligations;
    private final M4DueDatePolicy dueDates;
    private final IntegrationInbox inbox;
    private final ConfirmedContractValidator validator;
    private final ObjectMapper json;

    CoreM4Consumer(IntegrationEventLogRepository logs,TaxpayerRepository taxpayers,TaxConceptRepository concepts,
        ExternalObligationService obligations,M4DueDatePolicy dueDates,IntegrationInbox inbox,
        ConfirmedContractValidator validator,ObjectMapper json){
        this.logs=logs;this.taxpayers=taxpayers;this.concepts=concepts;this.obligations=obligations;
        this.dueDates=dueDates;this.inbox=inbox;this.validator=validator;this.json=json;
    }

    @Transactional IntegrationEventLog consumePermitFee(CoreM4Events.Envelope<CoreM4Events.PermitFeeGeneratedData> event){
        validate(event,"permitFeeGenerated");
        var data=event.data();
        return recordPending(event,ExternalObligationType.PERMIT_FEE,data.id(),data.cuit(),data.currency(),
            data.amount(),canonicalUuid(data.permitApplicationId())&&canonicalUuid(data.establishmentId()));
    }

    @Transactional IntegrationEventLog consumeCommercialFine(CoreM4Events.Envelope<CoreM4Events.CommercialFineGeneratedData> event){
        validate(event,"commercialFineGenerated");
        boolean hasSourceViolation=StringUtils.hasText(event.data().sourceViolationId());
        boolean hasSourceModule=StringUtils.hasText(event.data().sourceModule());
        CatalogService.require(hasSourceViolation==hasSourceModule,
            "INVALID_M4_VIOLATION_SOURCE","sourceViolationId y sourceModule deben informarse juntos");
        CatalogService.require(!hasSourceModule||"M6".equals(event.data().sourceModule()),
            "INVALID_M4_VIOLATION_SOURCE","sourceModule debe identificar a M6 cuando existe origen externo");
        CatalogService.require(event.data().decision()==null||"FINE".equals(event.data().decision()),
            "INVALID_M4_DECISION","La decisión de una multa comercial debe ser FINE");
        var data=event.data();
        return recordPending(event,ExternalObligationType.COMMERCIAL_FINE,data.id(),data.cuit(),data.currency(),
            data.amount(),canonicalUuid(data.establishmentId())&&canonicalUuid(data.actId())
                &&(!hasSourceViolation||canonicalUuid(data.sourceViolationId())));
    }

    private void validate(CoreM4Events.Envelope<?> event,String expected){
        validator.validate(event);
        CatalogService.require(event.eventId().version()==4,"INVALID_EVENT_ID","eventId debe ser UUIDv4");
        CatalogService.require("1.0".equals(event.eventVersion()),"UNSUPPORTED_EVENT_VERSION","Los eventos M4 sólo admiten eventVersion 1.0");
        CatalogService.require("habilitaciones".equals(event.sourceModule()),"INVALID_SOURCE_MODULE","El evento económico debe provenir de habilitaciones");
        CatalogService.require(expected.equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba "+expected);
    }

    private IntegrationEventLog recordPending(CoreM4Events.Envelope<?> event,ExternalObligationType type,
        String originId,String cuit,String currency,BigDecimal amount,boolean referencesValid){
        String businessKey=event.sourceModule()+":"+event.eventType()+":"+originId;
        var normalized=new NormalizedIntegrationEvent(event.eventId().toString(),event.eventType(),event.occurredAt(),
            event.sourceModule(),originId,json.valueToTree(event.data()),event.correlationId()==null?null:event.correlationId().toString());
        var sameEvent=logs.findFirstByExternalEventIdOrderByIdDesc(event.eventId().toString());
        if(sameEvent.isPresent()){
            IntegrationEventLog original=sameEvent.orElseThrow();
            try{
                CatalogService.require(businessKey.equals(original.businessKey)&&sameEvent(original.payload,event),
                    "M4_EVENT_ID_CONFLICT","El eventId ya existe con otro contenido");
            }catch(com.fasterxml.jackson.core.JsonProcessingException ex){
                throw new BusinessException("INVALID_EVENT_PAYLOAD","No se pudo comparar el evento original de M4",422);
            }
            return processOrDefer(original,event,type,originId,cuit,currency,amount,referencesValid);
        }
        if(logs.findFirstByBusinessKeyOrderByIdDesc(businessKey).isPresent())return inbox.ignore(normalized);
        IntegrationEventLog log=inbox.receive(normalized,event);
        log.businessKey=businessKey;
        return processOrDefer(log,event,type,originId,cuit,currency,amount,referencesValid);
    }

    private IntegrationEventLog processOrDefer(IntegrationEventLog log,CoreM4Events.Envelope<?> event,
        ExternalObligationType type,String originId,String cuit,String currency,BigDecimal amount,boolean referencesValid){
        if(log.status==IntegrationEventStatus.PROCESSED||log.status==IntegrationEventStatus.IGNORED)return log;
        if(!canonicalUuid(originId)||!referencesValid)inbox.fail(log,SOURCE_ID_PENDING);
        else if(event.correlationId()==null)inbox.fail(log,"M4_ORIGINAL_CORRELATION_ID_UNAVAILABLE");
        else if(!"ARS".equals(currency))inbox.fail(log,"M4_UNSUPPORTED_CURRENCY");
        else if(taxpayers.findByTaxpayerTypeAndCuit(TaxpayerType.ORGANIZATION,cuit).isEmpty())inbox.fail(log,TAXPAYER_PENDING);
        else if(concepts.findByCode(type.name()).filter(concept->concept.active).isEmpty())
            inbox.fail(log,"M4_TAX_CONCEPT_PENDING: "+type.name());
        else{
            java.util.Optional<java.time.LocalDate> dueDate;
            try{dueDate=dueDates.dueDate(type,event.occurredAt());}
            catch(BusinessException ex){inbox.fail(log,ex.code);return log;}
            if(dueDate.isEmpty())inbox.fail(log,DUE_DATE_PENDING+": falta configurar el plazo M5 para "+type.name());
            else{
                ExternalObligation obligation=obligations.createM4(event,type,originId,cuit,amount,dueDate.orElseThrow());
                if(obligation.status==ExternalObligationStatus.PROCESSED){
                    inbox.complete(new NormalizedIntegrationEvent(event.eventId().toString(),event.eventType(),
                        event.occurredAt(),event.sourceModule(),originId,json.valueToTree(event.data()),
                        event.correlationId().toString()),log);
                    log.errorMessage=null;
                }else inbox.fail(log,obligation.errorMessage);
            }
        }
        return log;
    }

    private boolean canonicalUuid(String value){
        try{return UUID.fromString(value).toString().equals(value);}catch(Exception ex){return false;}
    }

    private boolean sameEvent(String storedPayload,CoreM4Events.Envelope<?> event)
        throws com.fasterxml.jackson.core.JsonProcessingException {
        ObjectNode stored=(ObjectNode)json.readTree(storedPayload);
        ObjectNode incoming=(ObjectNode)json.valueToTree(event);
        if(!OffsetDateTime.parse(stored.path("occurredAt").asText()).isEqual(event.occurredAt()))return false;
        stored.remove("occurredAt");incoming.remove("occurredAt");
        JsonNode storedData=stored.path("data"),incomingData=incoming.path("data");
        if(storedData.has("decidedAt")||incomingData.has("decidedAt")){
            if(!storedData.has("decidedAt")||!incomingData.has("decidedAt")
                ||!OffsetDateTime.parse(storedData.path("decidedAt").asText()).isEqual(
                    OffsetDateTime.parse(incomingData.path("decidedAt").asText())))return false;
            ((ObjectNode)storedData).remove("decidedAt");((ObjectNode)incomingData).remove("decidedAt");
        }
        return stored.equals((left,right)->left.isNumber()&&right.isNumber()
            ?left.decimalValue().compareTo(right.decimalValue()):left.equals(right)?0:1,incoming);
    }
}
