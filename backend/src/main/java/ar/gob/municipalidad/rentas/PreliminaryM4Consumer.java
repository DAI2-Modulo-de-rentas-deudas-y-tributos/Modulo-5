package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

@Service
class PreliminaryM4Consumer {
    static final String TAXPAYER_BLOCKER="BLOCKED_M4_TAXPAYER_RESOLUTION";
    private final IntegrationEventLogRepository logs;
    private final IntegrationInbox inbox;
    private final ConfirmedContractValidator validator;
    private final PreliminaryM4EventMapper mapper;

    PreliminaryM4Consumer(IntegrationEventLogRepository logs,IntegrationInbox inbox,
        ConfirmedContractValidator validator,PreliminaryM4EventMapper mapper){this.logs=logs;this.inbox=inbox;this.validator=validator;this.mapper=mapper;}

    @Transactional IntegrationEventLog consumePermitFee(PreliminaryM4Events.Envelope<PreliminaryM4Events.PermitFeeGeneratedData> event){
        validateEnvelope(event,"permitFeeGenerated");
        return recordBlocked(event,event.data().id());
    }

    @Transactional IntegrationEventLog consumeCommercialFine(PreliminaryM4Events.Envelope<PreliminaryM4Events.CommercialFineGeneratedData> event){
        validateEnvelope(event,"commercialFineGenerated");
        boolean violation=StringUtils.hasText(event.data().sourceViolationId());
        boolean module=StringUtils.hasText(event.data().sourceModule());
        CatalogService.require(violation==module,"INVALID_M4_VIOLATION_SOURCE",
            "sourceViolationId y sourceModule deben informarse juntos");
        return recordBlocked(event,event.data().id());
    }

    private void validateEnvelope(PreliminaryM4Events.Envelope<?> event,String expected){
        validator.validate(event);
        CatalogService.require("M4".equals(event.module()),"INVALID_SOURCE_MODULE","El contrato preliminar requiere module M4");
        CatalogService.require(expected.equals(event.event()),"UNSUPPORTED_EVENT_TYPE","Se esperaba "+expected);
    }

    private IntegrationEventLog recordBlocked(PreliminaryM4Events.Envelope<?> event,String id){
        String key=mapper.businessKey(event,id);
        var existing=logs.findFirstByExternalEventIdOrderByIdDesc(key);
        if(existing.isPresent())return existing.get();
        OffsetDateTime receivedAt=OffsetDateTime.now();
        IntegrationEventLog log=inbox.receive(mapper.normalize(event,key,receivedAt),event);
        inbox.fail(log,TAXPAYER_BLOCKER+": establishmentId no tiene una resolución contractual hacia TaxpayerReference M5");
        return log;
    }
}
