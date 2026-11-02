package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cada guard de contrato del outbox manda a DLQ el evento que el consumidor no podría procesar
 * y deja pasar el que cumple el contrato acordado.
 */
class OutboxContractGuardTests {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String M7="M7_CONTRACT_REVIEW_REQUIRED";
    private static final String M4="M4_CONTRACT_REVIEW_REQUIRED";
    private static final String M8="M8_CONTRACT_REVIEW_REQUIRED";
    private static final String PLAN="M1_PLAN_CONTRACT_REVIEW_REQUIRED";

    @ParameterizedTest(name="{0}")
    @MethodSource("validEvents")
    void contractCompliantEventIsNotDeadLettered(String scenario,String eventType,String originType,Consumer<ObjectNode> change){
        OutboxEvent event=event(eventType,originType,change);
        EventPublisher publisher=publish(event);
        assertThat(event.status).isNotEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(event.errorMessage).isNull();
        if(event.status==OutboxStatus.LOCAL_ONLY)verify(publisher).publish(event.eventType,event.targetModule,event.payload);
    }

    @ParameterizedTest(name="{0}")
    @MethodSource("invalidEvents")
    void contractViolationIsDeadLetteredBeforePublishing(String scenario,String eventType,String originType,
        Consumer<ObjectNode> change,String expectedCode){
        OutboxEvent event=event(eventType,originType,change);
        EventPublisher publisher=publish(event);
        assertThat(event.status).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(event.errorMessage).startsWith(expectedCode);
        verifyNoInteractions(publisher);
    }

    static Stream<Arguments> validEvents(){
        return Stream.of(
            arguments("M7 paymentRegistered","paymentRegistered","TRAFFIC_FINE",none()),
            arguments("M7 paymentReversed sin causationId","paymentReversed","TRAFFIC_FINE",(Consumer<ObjectNode>)e->e.putNull("causationId")),
            arguments("M7 debtSettled","debtSettled","TRAFFIC_FINE",none()),
            arguments("M4 paymentRegistered de tasa","paymentRegistered","PERMIT_FEE",none()),
            arguments("M4 paymentReversed de multa","paymentReversed","COMMERCIAL_FINE",none()),
            arguments("M4 debtSettled sin causationId","debtSettled","PERMIT_FEE",(Consumer<ObjectNode>)e->e.putNull("causationId")),
            arguments("origen propio de M5","paymentRegistered","LIQUIDATION",none()),
            arguments("exemptionRequested","exemptionRequested",null,none()),
            arguments("updateExemptionStatus APPROVED","updateExemptionStatus",null,none()),
            arguments("updateExemptionStatus REJECTED","updateExemptionStatus",null,(Consumer<ObjectNode>)e->{data(e).put("status","REJECTED");data(e).remove("exemptionId");}),
            arguments("paymentPlanRequested","paymentPlanRequested",null,none()),
            arguments("updatePaymentPlanStatus GRANTED","updatePaymentPlanStatus",null,none()),
            arguments("updatePaymentPlanStatus REJECTED","updatePaymentPlanStatus",null,(Consumer<ObjectNode>)e->rejectedPlan(data(e))));
    }

    static Stream<Arguments> invalidEvents(){
        return Stream.of(
            invalid("M7 originId no canónico","paymentRegistered","TRAFFIC_FINE",e->data(e).put("originId",data(e).path("originId").asText().toUpperCase(Locale.ROOT)),M7),
            invalid("M7 infractionId distinto del origen","paymentRegistered","TRAFFIC_FINE",e->data(e).put("infractionId",UUID.randomUUID().toString()),M7),
            invalid("M7 eventId distinto del outbox","paymentRegistered","TRAFFIC_FINE",e->e.put("eventId",UUID.randomUUID().toString()),M7),
            invalid("M7 eventType distinto del outbox","paymentRegistered","TRAFFIC_FINE",e->e.put("eventType","paymentReversed"),M7),
            invalid("M7 sourceModule ajeno","paymentRegistered","TRAFFIC_FINE",e->e.put("sourceModule","transito"),M7),
            invalid("M7 eventVersion no acordada","paymentRegistered","TRAFFIC_FINE",e->e.put("eventVersion","2.0"),M7),
            invalid("M7 correlationId no canónico","paymentRegistered","TRAFFIC_FINE",e->e.put("correlationId",e.path("correlationId").asText().toUpperCase(Locale.ROOT)),M7),
            invalid("M7 causationId no canónico","paymentRegistered","TRAFFIC_FINE",e->e.put("causationId",e.path("causationId").asText().toUpperCase(Locale.ROOT)),M7),
            invalid("M7 importe no numérico","paymentRegistered","TRAFFIC_FINE",e->data(e).put("amountApplied","10"),M7),
            invalid("M7 importe cero","paymentRegistered","TRAFFIC_FINE",e->data(e).put("amountApplied",0),M7),
            invalid("M7 saldo no numérico","paymentRegistered","TRAFFIC_FINE",e->data(e).put("remainingBalance","20"),M7),
            invalid("M7 saldo negativo","paymentRegistered","TRAFFIC_FINE",e->data(e).put("remainingBalance",-1),M7),
            invalid("M7 fecha de pago inválida","paymentRegistered","TRAFFIC_FINE",e->data(e).put("paidAt","ayer"),M7),
            invalid("M7 reversión sin motivo","paymentReversed","TRAFFIC_FINE",e->data(e).put("reason"," "),M7),
            invalid("M7 reversión sin importe","paymentReversed","TRAFFIC_FINE",e->data(e).remove("reversedAmount"),M7),
            invalid("M7 cancelación con otro concepto","debtSettled","TRAFFIC_FINE",e->data(e).put("conceptCode","TASA_SERVICIOS"),M7),
            invalid("M7 cancelación con saldo no numérico","debtSettled","TRAFFIC_FINE",e->data(e).put("remainingBalance","0"),M7),
            invalid("M7 cancelación con saldo pendiente","debtSettled","TRAFFIC_FINE",e->data(e).put("remainingBalance",5),M7),
            invalid("M7 cancelación con deuda no numérica","debtSettled","TRAFFIC_FINE",e->data(e).put("outstandingBalance","0"),M7),
            invalid("M7 cancelación con deuda pendiente","debtSettled","TRAFFIC_FINE",e->data(e).put("outstandingBalance",5),M7),
            invalid("M7 cancelación con fecha inválida","debtSettled","TRAFFIC_FINE",e->data(e).put("settledAt","hoy"),M7),

            invalid("M4 originId no canónico","paymentRegistered","PERMIT_FEE",e->data(e).put("originId",data(e).path("originId").asText().toUpperCase(Locale.ROOT)),M4),
            invalid("M4 con infractionId","paymentRegistered","PERMIT_FEE",e->data(e).put("infractionId",UUID.randomUUID().toString()),M4),
            invalid("M4 eventId distinto del outbox","paymentRegistered","PERMIT_FEE",e->e.put("eventId",UUID.randomUUID().toString()),M4),
            invalid("M4 eventType distinto del outbox","paymentRegistered","PERMIT_FEE",e->e.put("eventType","debtSettled"),M4),
            invalid("M4 eventVersion no acordada","paymentRegistered","COMMERCIAL_FINE",e->e.put("eventVersion","2.0"),M4),
            invalid("M4 sourceModule ajeno","paymentRegistered","PERMIT_FEE",e->e.put("sourceModule","habilitaciones"),M4),
            invalid("M4 correlationId no canónico","paymentRegistered","PERMIT_FEE",e->e.put("correlationId",e.path("correlationId").asText().toUpperCase(Locale.ROOT)),M4),
            invalid("M4 causationId inválido","paymentRegistered","PERMIT_FEE",e->e.put("causationId","no-es-uuid"),M4),
            invalid("M4 importe no numérico","paymentRegistered","PERMIT_FEE",e->data(e).put("amountApplied","10"),M4),
            invalid("M4 importe cero","paymentRegistered","PERMIT_FEE",e->data(e).put("amountApplied",0),M4),
            invalid("M4 saldo no numérico","paymentRegistered","PERMIT_FEE",e->data(e).put("remainingBalance","20"),M4),
            invalid("M4 saldo negativo","paymentRegistered","PERMIT_FEE",e->data(e).put("remainingBalance",-1),M4),
            invalid("M4 fecha de pago inválida","paymentRegistered","PERMIT_FEE",e->data(e).put("paidAt","ayer"),M4),
            invalid("M4 reversión sin motivo","paymentReversed","PERMIT_FEE",e->data(e).remove("reason"),M4),
            invalid("M4 cancelación sin ciudadano","debtSettled","PERMIT_FEE",e->data(e).remove("citizenId"),M4),
            invalid("M4 cancelación sin concepto","debtSettled","PERMIT_FEE",e->data(e).put("conceptCode"," "),M4),
            invalid("M4 cancelación con saldo no numérico","debtSettled","PERMIT_FEE",e->data(e).put("remainingBalance","0"),M4),
            invalid("M4 cancelación con saldo pendiente","debtSettled","PERMIT_FEE",e->data(e).put("remainingBalance",5),M4),
            invalid("M4 cancelación con deuda no numérica","debtSettled","PERMIT_FEE",e->data(e).put("outstandingBalance","0"),M4),
            invalid("M4 cancelación con deuda pendiente","debtSettled","PERMIT_FEE",e->data(e).put("outstandingBalance",5),M4),
            invalid("M4 cancelación con fecha inválida","debtSettled","PERMIT_FEE",e->data(e).put("settledAt","hoy"),M4),

            invalid("exemptionRequested con requestId legado","exemptionRequested",null,e->data(e).put("requestId","exr-1"),M8),
            invalid("exemptionRequested con citizenId legado","exemptionRequested",null,e->data(e).put("citizenId","citizen-1"),M8),
            invalid("APPROVED con exemptionId legado","updateExemptionStatus",null,e->data(e).put("exemptionId","ex-1"),M8),
            invalid("estado de exención no soportado","updateExemptionStatus",null,e->data(e).put("status","PENDING"),M8),

            invalid("paymentPlanRequested con requestId legado","paymentPlanRequested",null,e->data(e).put("requestId","ppr-1"),PLAN),
            invalid("paymentPlanRequested con citizenId legado","paymentPlanRequested",null,e->data(e).put("citizenId","citizen-1"),PLAN),
            invalid("paymentPlanRequested sin lista de deudas","paymentPlanRequested",null,e->data(e).put("debtIds","debt-1"),PLAN),
            invalid("paymentPlanRequested con deudas vacías","paymentPlanRequested",null,e->data(e).putArray("debtIds"),PLAN),
            invalid("paymentPlanRequested con total cero","paymentPlanRequested",null,e->data(e).put("totalDebt",0),PLAN),
            invalid("paymentPlanRequested sin cuotas","paymentPlanRequested",null,e->data(e).put("installments",0),PLAN),
            invalid("paymentPlanRequested con deuda legada","paymentPlanRequested",null,e->data(e).putArray("debtIds").add("debt-1"),PLAN),
            invalid("GRANTED con planId legado","updatePaymentPlanStatus",null,e->data(e).put("planId","plan-1"),PLAN),
            invalid("GRANTED sin cuotas","updatePaymentPlanStatus",null,e->data(e).put("installments",0),PLAN),
            invalid("GRANTED con total cero","updatePaymentPlanStatus",null,e->data(e).put("totalAmount",0),PLAN),
            invalid("GRANTED con motivo","updatePaymentPlanStatus",null,e->data(e).put("reason","Motivo"),PLAN),
            invalid("REJECTED con plan","updatePaymentPlanStatus",null,e->{rejectedPlan(data(e));data(e).put("planId",UUID.randomUUID().toString());},PLAN),
            invalid("REJECTED con cuotas","updatePaymentPlanStatus",null,e->{rejectedPlan(data(e));data(e).put("installments",3);},PLAN),
            invalid("REJECTED con total","updatePaymentPlanStatus",null,e->{rejectedPlan(data(e));data(e).put("totalAmount",10);},PLAN),
            invalid("REJECTED sin motivo","updatePaymentPlanStatus",null,e->{rejectedPlan(data(e));data(e).put("reason"," ");},PLAN),
            invalid("estado de plan no soportado","updatePaymentPlanStatus",null,e->data(e).put("status","PENDING"),PLAN));
    }

    private static Arguments invalid(String scenario,String eventType,String originType,Consumer<ObjectNode> change,String code){
        return arguments(scenario,eventType,originType,change,code);
    }
    private static Consumer<ObjectNode> none(){return e->{};}
    private static ObjectNode data(ObjectNode envelope){return (ObjectNode)envelope.get("data");}
    private static void rejectedPlan(ObjectNode data){
        data.put("status","REJECTED");data.remove("planId");data.remove("installments");data.remove("totalAmount");data.put("reason","No cumple");
    }

    private static EventPublisher publish(OutboxEvent event){
        OutboxRepository repository=mock(OutboxRepository.class);
        EventPublisher publisher=mock(EventPublisher.class);
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));
        new OutboxPublisher(repository,publisher).publishPending();
        return publisher;
    }

    /** Evento que cumple el contrato; {@code change} lo altera antes de serializarlo. */
    private static OutboxEvent event(String eventType,String originType,Consumer<ObjectNode> change){
        OutboxEvent event=new OutboxEvent();
        event.id=UUID.randomUUID();
        event.eventType=eventType;
        event.targetModule="CORE";
        event.aggregateType="PaymentAllocation";
        event.aggregateId="1";
        event.status=OutboxStatus.PENDING;
        event.retryCount=0;
        event.createdAt=OffsetDateTime.now();
        ObjectNode envelope=JSON.createObjectNode();
        envelope.put("eventId",event.id.toString());
        envelope.put("eventType",eventType);
        envelope.put("eventVersion","1.0");
        envelope.put("sourceModule","rentas");
        envelope.put("correlationId",UUID.randomUUID().toString());
        envelope.put("causationId",UUID.randomUUID().toString());
        envelope.set("data",data(eventType,originType));
        change.accept(envelope);
        event.payload=envelope.toString();
        return event;
    }

    private static ObjectNode data(String eventType,String originType){
        ObjectNode data=JSON.createObjectNode();
        String origin=UUID.randomUUID().toString();
        switch(eventType){
            case "paymentRegistered","paymentReversed","debtSettled"->{
                data.put("debtId",UUID.randomUUID().toString());
                data.put("originType",originType);
                data.put("originId",origin);
                if("TRAFFIC_FINE".equals(originType))data.put("infractionId",origin);
                if("paymentRegistered".equals(eventType)){
                    data.put("paymentId",UUID.randomUUID().toString());
                    data.put("amountApplied",10);data.put("paidAt","2026-10-27T10:00:00-03:00");data.put("remainingBalance",20);
                }else if("paymentReversed".equals(eventType)){
                    data.put("paymentId",UUID.randomUUID().toString());
                    data.put("reversedAmount",10);data.put("reversedAt","2026-10-28T10:00:00-03:00");
                    data.put("remainingBalance",30);data.put("reason","Pago registrado por error");
                }else{
                    data.put("conceptCode","TRAFFIC_FINE".equals(originType)?"TRAFFIC_INFRACTION":"TASA_HABILITACION");
                    if(!"TRAFFIC_FINE".equals(originType))data.put("citizenId",UUID.randomUUID().toString());
                    data.put("remainingBalance",0);data.put("outstandingBalance",0);data.put("settledAt","2026-10-27T10:00:00-03:00");
                }
            }
            case "exemptionRequested"->{
                data.put("requestId",UUID.randomUUID().toString());data.put("citizenId",UUID.randomUUID().toString());
            }
            case "updateExemptionStatus"->{
                data.put("requestId",UUID.randomUUID().toString());data.put("citizenId",UUID.randomUUID().toString());
                data.put("status","APPROVED");data.put("exemptionId",UUID.randomUUID().toString());
            }
            case "paymentPlanRequested"->{
                data.put("requestId",UUID.randomUUID().toString());data.put("citizenId",UUID.randomUUID().toString());
                data.putArray("debtIds").add(UUID.randomUUID().toString());data.put("totalDebt",200000);data.put("installments",6);
            }
            case "updatePaymentPlanStatus"->{
                data.put("requestId",UUID.randomUUID().toString());data.put("citizenId",UUID.randomUUID().toString());
                data.put("status","GRANTED");data.put("planId",UUID.randomUUID().toString());
                data.put("installments",6);data.put("totalAmount",220000);
            }
            default->throw new IllegalArgumentException(eventType);
        }
        return data;
    }
}
