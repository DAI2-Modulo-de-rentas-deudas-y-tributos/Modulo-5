package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Un snapshot diferido sólo se reutiliza si describe exactamente el mismo movimiento económico. */
class EconomicMovementSnapshotTests {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final UUID SOURCE=UUID.fromString("67ea68d5-f946-4016-b563-abc6e81d29c1");
    private static final EconomicMovementSnapshot BASE=snapshot(data("{\"amountApplied\":10}"));

    @Test void sameMovementIgnoresEventIdAndOccurrence(){
        var replay=new EconomicMovementSnapshot(BASE.eventType(),BASE.aggregateType(),BASE.aggregateId(),BASE.debtId(),
            BASE.paymentId(),BASE.allocationId(),BASE.sourceEventId(),BASE.originId(),BASE.targetModule(),
            BASE.idempotencyKey(),UUID.randomUUID(),OffsetDateTime.now(),data("{\"amountApplied\":10}"));
        assertThat(BASE.sameMovement(replay)).isTrue();
    }

    @Test void nullIsNeverTheSameMovement(){
        assertThat(BASE.sameMovement(null)).isFalse();
    }

    @ParameterizedTest(name="{0}")
    @MethodSource("changes")
    void anyBusinessDifferenceIsADifferentMovement(String field,UnaryOperator<Fields> change){
        assertThat(BASE.sameMovement(change.apply(Fields.of(BASE)).build())).isFalse();
    }

    static Stream<Arguments> changes(){
        return Stream.of(
            arguments("eventType",(UnaryOperator<Fields>)f->f.with(0,"paymentReversed")),
            arguments("aggregateType",(UnaryOperator<Fields>)f->f.with(1,"Payment")),
            arguments("aggregateId",(UnaryOperator<Fields>)f->f.with(2,99L)),
            arguments("debtId",(UnaryOperator<Fields>)f->f.with(3,99L)),
            arguments("paymentId",(UnaryOperator<Fields>)f->f.with(4,99L)),
            arguments("allocationId",(UnaryOperator<Fields>)f->f.with(5,99L)),
            arguments("sourceEventId",(UnaryOperator<Fields>)f->f.with(6,UUID.randomUUID())),
            arguments("originId",(UnaryOperator<Fields>)f->f.with(7,"otro-origen")),
            arguments("targetModule",(UnaryOperator<Fields>)f->f.with(8,"M7")),
            arguments("idempotencyKey",(UnaryOperator<Fields>)f->f.with(9,"otra-clave")),
            arguments("data",(UnaryOperator<Fields>)f->f.with(12,data("{\"amountApplied\":11}"))));
    }

    /** Copia mutable de los componentes del record para cambiar uno por vez. */
    record Fields(Object[] values){
        static Fields of(EconomicMovementSnapshot s){
            return new Fields(new Object[]{s.eventType(),s.aggregateType(),s.aggregateId(),s.debtId(),s.paymentId(),
                s.allocationId(),s.sourceEventId(),s.originId(),s.targetModule(),s.idempotencyKey(),s.eventId(),
                s.occurredAt(),s.data()});
        }
        Fields with(int index,Object value){Object[] copy=values.clone();copy[index]=value;return new Fields(copy);}
        EconomicMovementSnapshot build(){
            return new EconomicMovementSnapshot((String)values[0],(String)values[1],(Long)values[2],(Long)values[3],
                (Long)values[4],(Long)values[5],(UUID)values[6],(String)values[7],(String)values[8],(String)values[9],
                (UUID)values[10],(OffsetDateTime)values[11],(JsonNode)values[12]);
        }
    }

    private static EconomicMovementSnapshot snapshot(JsonNode data){
        return new EconomicMovementSnapshot("paymentRegistered","PaymentAllocation",1L,2L,3L,4L,SOURCE,
            "origin-1","M4","payment-allocation:4",UUID.randomUUID(),OffsetDateTime.now(),data);
    }
    private static JsonNode data(String value){
        try{return JSON.readTree(value);}catch(Exception ex){throw new IllegalStateException(ex);}
    }
}
