package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
class CoreM4Consumer {
    static final String DUE_DATE_PENDING="M4_DUE_DATE_POLICY_PENDING";
    static final String TAXPAYER_PENDING="M4_TAXPAYER_NOT_FOUND";
    private final IntegrationEventLogRepository logs;
    private final TaxpayerRepository taxpayers;
    private final IntegrationInbox inbox;
    private final ConfirmedContractValidator validator;
    private final ObjectMapper json;

    CoreM4Consumer(IntegrationEventLogRepository logs,TaxpayerRepository taxpayers,
        IntegrationInbox inbox,ConfirmedContractValidator validator,ObjectMapper json){
        this.logs=logs;this.taxpayers=taxpayers;this.inbox=inbox;this.validator=validator;this.json=json;
    }

    @Transactional IntegrationEventLog consumePermitFee(CoreM4Events.Envelope<CoreM4Events.PermitFeeGeneratedData> event){
        validate(event,"permitFeeGenerated");
        return recordPending(event,event.data().id(),event.data().cuit(),event.data().currency());
    }

    @Transactional IntegrationEventLog consumeCommercialFine(CoreM4Events.Envelope<CoreM4Events.CommercialFineGeneratedData> event){
        validate(event,"commercialFineGenerated");
        boolean hasSourceViolation=StringUtils.hasText(event.data().sourceViolationId());
        boolean hasSourceModule=StringUtils.hasText(event.data().sourceModule());
        CatalogService.require(hasSourceViolation==hasSourceModule,
            "INVALID_M4_VIOLATION_SOURCE","sourceViolationId y sourceModule deben informarse juntos");
        CatalogService.require(!hasSourceModule||"M6".equals(event.data().sourceModule()),
            "INVALID_M4_VIOLATION_SOURCE","sourceModule debe identificar a M6 cuando existe origen externo");
        return recordPending(event,event.data().id(),event.data().cuit(),event.data().currency());
    }

    private void validate(CoreM4Events.Envelope<?> event,String expected){
        validator.validate(event);
        CatalogService.require(event.eventId().version()==4,"INVALID_EVENT_ID","eventId debe ser UUIDv4");
        CatalogService.require("1.0".equals(event.eventVersion()),"UNSUPPORTED_EVENT_VERSION","Los eventos M4 sólo admiten eventVersion 1.0");
        CatalogService.require("habilitaciones".equals(event.sourceModule()),"INVALID_SOURCE_MODULE","El evento económico debe provenir de habilitaciones");
        CatalogService.require(expected.equals(event.eventType()),"UNSUPPORTED_EVENT_TYPE","Se esperaba "+expected);
    }

    private IntegrationEventLog recordPending(CoreM4Events.Envelope<?> event,String originId,String cuit,String currency){
        String businessKey=event.sourceModule()+":"+event.eventType()+":"+originId;
        var normalized=new NormalizedIntegrationEvent(event.eventId().toString(),event.eventType(),event.occurredAt(),
            event.sourceModule(),originId,json.valueToTree(event.data()),event.correlationId()==null?null:event.correlationId().toString());
        var sameEvent=logs.findFirstByExternalEventIdOrderByIdDesc(event.eventId().toString());
        if(sameEvent.isPresent())return markPending(sameEvent.get(),cuit,currency);
        if(logs.findFirstByBusinessKeyOrderByIdDesc(businessKey).isPresent())return inbox.ignore(normalized);
        IntegrationEventLog log=inbox.receive(normalized,event);
        log.businessKey=businessKey;
        return markPending(log,cuit,currency);
    }

    private IntegrationEventLog markPending(IntegrationEventLog log,String cuit,String currency){
        if(!"ARS".equals(currency))inbox.fail(log,"M4_UNSUPPORTED_CURRENCY");
        else if(taxpayers.findByTaxpayerTypeAndCuit(TaxpayerType.ORGANIZATION,cuit).isEmpty())inbox.fail(log,TAXPAYER_PENDING);
        else inbox.fail(log,DUE_DATE_PENDING+": M4 no envía fecha de vencimiento; falta definir política M5");
        return log;
    }
}
