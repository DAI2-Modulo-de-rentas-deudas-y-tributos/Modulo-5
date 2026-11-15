package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test") @SpringBootTest @Transactional
class M4PreliminaryContractTests {
    private static final String PERMIT_JSON="""
        {"module":"M4","event":"permitFeeGenerated","data":{"id":"FEE-001","permitApplicationId":"PA-001","establishmentId":"EST-001","amount":50000}}
        """;
    private static final String FINE_JSON="""
        {"module":"M4","event":"commercialFineGenerated","data":{"id":"FINE-001","sourceViolationId":"V-001","sourceModule":"M6","establishmentId":"EST-001","actId":"ACT-001","amount":100000,"reason":"Incumplimiento comercial","decidedAt":"2026-08-24T15:00:00-03:00","externalRef":"MULTA-2026-00123"}}
        """;

    @Autowired ObjectMapper json;@Autowired PreliminaryM4Consumer consumer;@Autowired IntegrationEventLogRepository logs;
    @Autowired ExternalObligationRepository obligations;@Autowired DebtRepository debts;

    @Test void parsesExactPermitContractAndBlocksEconomicEffectIdempotently()throws Exception{
        var event=json.readValue(PERMIT_JSON,new TypeReference<PreliminaryM4Events.Envelope<PreliminaryM4Events.PermitFeeGeneratedData>>(){});
        assertThat(event.module()).isEqualTo("M4");assertThat(event.event()).isEqualTo("permitFeeGenerated");
        assertThat(event.data().id()).isEqualTo("FEE-001");assertThat(event.data().permitApplicationId()).isEqualTo("PA-001");
        assertThat(event.data().establishmentId()).isEqualTo("EST-001");assertThat(event.data().amount()).isEqualByComparingTo("50000");
        long obligationCount=obligations.count(),debtCount=debts.count(),logCount=logs.count();
        IntegrationEventLog first=consumer.consumePermitFee(event),duplicate=consumer.consumePermitFee(event);
        assertThat(duplicate.id).isEqualTo(first.id);assertThat(logs.count()).isEqualTo(logCount+1);
        assertBlocked(first,"M4:permitFeeGenerated:FEE-001");assertThat(first.payload).contains("PA-001","EST-001","50000");
        assertThat(obligations.count()).isEqualTo(obligationCount);assertThat(debts.count()).isEqualTo(debtCount);
    }

    @Test void parsesExactCommercialFineContractAndPreservesTraceability()throws Exception{
        var event=json.readValue(FINE_JSON,new TypeReference<PreliminaryM4Events.Envelope<PreliminaryM4Events.CommercialFineGeneratedData>>(){});
        assertThat(event.data().amount()).isEqualByComparingTo("100000");assertThat(event.data().decidedAt().toInstant()).isEqualTo(java.time.Instant.parse("2026-08-24T18:00:00Z"));
        IntegrationEventLog log=consumer.consumeCommercialFine(event);assertBlocked(log,"M4:commercialFineGenerated:FINE-001");
        assertThat(log.payload).contains("V-001","M6","EST-001","ACT-001","Incumplimiento comercial","MULTA-2026-00123");
    }

    @Test void acceptsFineWithoutExternalViolationCorrelation(){
        var data=new PreliminaryM4Events.CommercialFineGeneratedData("FINE-LOCAL",null,null,"EST-2","ACT-2",new BigDecimal("1.01"),"Acta local",java.time.OffsetDateTime.parse("2026-08-24T15:00:00-03:00"),"MULTA-2");
        assertBlocked(consumer.consumeCommercialFine(new PreliminaryM4Events.Envelope<>("M4","commercialFineGenerated",data)),"M4:commercialFineGenerated:FINE-LOCAL");
    }

    @Test void rejectsIncompleteViolationCorrelation(){
        var data=new PreliminaryM4Events.CommercialFineGeneratedData("FINE-BAD","V-1",null,"EST-2","ACT-2",BigDecimal.ONE,"Acta",java.time.OffsetDateTime.now(),"MULTA-3");
        assertThatThrownBy(()->consumer.consumeCommercialFine(new PreliminaryM4Events.Envelope<>("M4","commercialFineGenerated",data))).isInstanceOf(BusinessException.class).hasMessageContaining("deben informarse juntos");
    }

    @Test void rejectsNullZeroAndNegativeAmounts(){
        assertInvalidAmount(null);assertInvalidAmount(BigDecimal.ZERO);assertInvalidAmount(new BigDecimal("-0.01"));
    }

    @Test void rejectsWrongModuleAndEvent(){
        var data=new PreliminaryM4Events.PermitFeeGeneratedData("FEE-X","PA-X","EST-X",BigDecimal.ONE);
        assertThatThrownBy(()->consumer.consumePermitFee(new PreliminaryM4Events.Envelope<>("M5","permitFeeGenerated",data))).isInstanceOf(BusinessException.class).hasMessageContaining("module M4");
        assertThatThrownBy(()->consumer.consumePermitFee(new PreliminaryM4Events.Envelope<>("M4","permitUpdate",data))).isInstanceOf(BusinessException.class).hasMessageContaining("permitFeeGenerated");
    }

    private void assertInvalidAmount(BigDecimal amount){
        var data=new PreliminaryM4Events.PermitFeeGeneratedData("FEE-BAD","PA-BAD","EST-BAD",amount);
        assertThatThrownBy(()->consumer.consumePermitFee(new PreliminaryM4Events.Envelope<>("M4","permitFeeGenerated",data))).isInstanceOf(BusinessException.class);
    }
    private void assertBlocked(IntegrationEventLog log,String key){assertThat(log.externalEventId).isEqualTo(key);assertThat(log.sourceModule).isEqualTo("M4");assertThat(log.status).isEqualTo(IntegrationEventStatus.FAILED);assertThat(log.errorMessage).startsWith(PreliminaryM4Consumer.TAXPAYER_BLOCKER);}
}
