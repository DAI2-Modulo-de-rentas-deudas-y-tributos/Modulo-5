package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Cada payload saliente rechaza en su constructor cualquier dato que el consumidor no pueda procesar. */
class ConfirmedOutboundEventsValidationTests {
    private static final UUID ID=UUID.fromString("7d6a54b0-5e58-4ed2-a1d1-94bde2fc1d41");
    private static final UUID CITIZEN=UUID.fromString("1bc4530b-27da-4f48-b7b6-03d27a7f0be4");
    private static final BigDecimal HALF=new BigDecimal("50.00");
    private static final LocalDate FROM=LocalDate.of(2026,9,1);
    private static final LocalDate UNTIL=LocalDate.of(2027,8,31);

    @Test void validPayloadsAreAccepted(){
        assertThat(new ConfirmedOutboundEvents.ExemptionRequestedPayload(ID,CITIZEN,"TASA_SERVICIOS","Situación socioeconómica",new BigDecimal("100"),FROM,UNTIL).requestId()).isEqualTo(ID);
        assertThat(new ConfirmedOutboundEvents.ExemptionRequestedPayload(ID,CITIZEN,"TASA_SERVICIOS","Motivo",HALF,FROM,FROM).requestedUntil()).isEqualTo(FROM);
        assertThat(new ConfirmedOutboundEvents.UpdateExemptionStatusPayload(ID,CITIZEN,"APPROVED",ID,"TASA_SERVICIOS",HALF,FROM,UNTIL,null).status()).isEqualTo("APPROVED");
        assertThat(new ConfirmedOutboundEvents.UpdateExemptionStatusPayload(ID,CITIZEN,"APPROVED",ID,"TASA_SERVICIOS",new BigDecimal("100"),FROM,null,null).validUntil()).isNull();
        assertThat(new ConfirmedOutboundEvents.UpdateExemptionStatusPayload(ID,CITIZEN,"REJECTED",null,"TASA_SERVICIOS",null,null,null,"No cumple").reason()).isEqualTo("No cumple");
        assertThat(new ConfirmedOutboundEvents.PaymentPlanRequestedPayload(ID,CITIZEN,List.of(ID),HALF,6).debtIds()).containsExactly(ID);
        assertThat(new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload(ID,CITIZEN,"GRANTED",ID,6,HALF,null).installments()).isEqualTo(6);
        assertThat(new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload(ID,CITIZEN,"REJECTED",null,null,null,"No cumple").reason()).isEqualTo("No cumple");
        assertThat(new ConfirmedOutboundEvents.OverdueDebtPayload(ID,CITIZEN,"TASA_SERVICIOS",HALF,FROM).dueDate()).isEqualTo(FROM);
    }

    @Test void paymentPlanRequestKeepsAnImmutableCopyOfDebtIds(){
        var debts=new java.util.ArrayList<>(List.of(ID));
        var payload=new ConfirmedOutboundEvents.PaymentPlanRequestedPayload(ID,CITIZEN,debts,HALF,3);
        debts.add(CITIZEN);
        assertThat(payload.debtIds()).containsExactly(ID);
    }

    @ParameterizedTest(name="{0}")
    @MethodSource("invalidPayloads")
    void invalidPayloadIsRejected(String scenario,Runnable construction){
        assertThatThrownBy(construction::run).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidPayloads(){
        return Stream.of(
            invalid("exemptionRequested sin requestId",()->exemptionRequested(null,CITIZEN,"C","Motivo",HALF,FROM,UNTIL)),
            invalid("exemptionRequested sin ciudadano",()->exemptionRequested(ID,null,"C","Motivo",HALF,FROM,UNTIL)),
            invalid("exemptionRequested sin concepto",()->exemptionRequested(ID,CITIZEN,null,"Motivo",HALF,FROM,UNTIL)),
            invalid("exemptionRequested con concepto en blanco",()->exemptionRequested(ID,CITIZEN," ","Motivo",HALF,FROM,UNTIL)),
            invalid("exemptionRequested sin motivo",()->exemptionRequested(ID,CITIZEN,"C"," ",HALF,FROM,UNTIL)),
            invalid("exemptionRequested sin porcentaje",()->exemptionRequested(ID,CITIZEN,"C","Motivo",null,FROM,UNTIL)),
            invalid("exemptionRequested con porcentaje cero",()->exemptionRequested(ID,CITIZEN,"C","Motivo",BigDecimal.ZERO,FROM,UNTIL)),
            invalid("exemptionRequested con porcentaje mayor a 100",()->exemptionRequested(ID,CITIZEN,"C","Motivo",new BigDecimal("100.01"),FROM,UNTIL)),
            invalid("exemptionRequested sin inicio",()->exemptionRequested(ID,CITIZEN,"C","Motivo",HALF,null,UNTIL)),
            invalid("exemptionRequested sin fin",()->exemptionRequested(ID,CITIZEN,"C","Motivo",HALF,FROM,null)),
            invalid("exemptionRequested con fin anterior al inicio",()->exemptionRequested(ID,CITIZEN,"C","Motivo",HALF,UNTIL,FROM)),

            invalid("updateExemptionStatus sin requestId",()->exemptionStatus(null,CITIZEN,"APPROVED",ID,"C",HALF,FROM,UNTIL,null)),
            invalid("updateExemptionStatus sin ciudadano",()->exemptionStatus(ID,null,"APPROVED",ID,"C",HALF,FROM,UNTIL,null)),
            invalid("updateExemptionStatus sin concepto",()->exemptionStatus(ID,CITIZEN,"APPROVED",ID," ",HALF,FROM,UNTIL,null)),
            invalid("APPROVED sin exención",()->exemptionStatus(ID,CITIZEN,"APPROVED",null,"C",HALF,FROM,UNTIL,null)),
            invalid("APPROVED sin porcentaje",()->exemptionStatus(ID,CITIZEN,"APPROVED",ID,"C",null,FROM,UNTIL,null)),
            invalid("APPROVED con porcentaje cero",()->exemptionStatus(ID,CITIZEN,"APPROVED",ID,"C",BigDecimal.ZERO,FROM,UNTIL,null)),
            invalid("APPROVED con porcentaje mayor a 100",()->exemptionStatus(ID,CITIZEN,"APPROVED",ID,"C",new BigDecimal("101"),FROM,UNTIL,null)),
            invalid("APPROVED sin inicio",()->exemptionStatus(ID,CITIZEN,"APPROVED",ID,"C",HALF,null,UNTIL,null)),
            invalid("APPROVED con fin anterior al inicio",()->exemptionStatus(ID,CITIZEN,"APPROVED",ID,"C",HALF,UNTIL,FROM,null)),
            invalid("REJECTED con exención",()->exemptionStatus(ID,CITIZEN,"REJECTED",ID,"C",null,null,null,"Motivo")),
            invalid("REJECTED con porcentaje",()->exemptionStatus(ID,CITIZEN,"REJECTED",null,"C",HALF,null,null,"Motivo")),
            invalid("REJECTED con inicio",()->exemptionStatus(ID,CITIZEN,"REJECTED",null,"C",null,FROM,null,"Motivo")),
            invalid("REJECTED con fin",()->exemptionStatus(ID,CITIZEN,"REJECTED",null,"C",null,null,UNTIL,"Motivo")),
            invalid("REJECTED sin motivo",()->exemptionStatus(ID,CITIZEN,"REJECTED",null,"C",null,null,null," ")),
            invalid("estado de exención no soportado",()->exemptionStatus(ID,CITIZEN,"PENDING",null,"C",null,null,null,null)),

            invalid("paymentPlanRequested sin requestId",()->planRequested(null,CITIZEN,List.of(ID),HALF,3)),
            invalid("paymentPlanRequested sin ciudadano",()->planRequested(ID,null,List.of(ID),HALF,3)),
            invalid("paymentPlanRequested sin deudas",()->planRequested(ID,CITIZEN,null,HALF,3)),
            invalid("paymentPlanRequested con deudas vacías",()->planRequested(ID,CITIZEN,List.of(),HALF,3)),
            invalid("paymentPlanRequested con una deuda nula",()->planRequested(ID,CITIZEN,Arrays.asList(ID,null),HALF,3)),
            invalid("paymentPlanRequested sin total",()->planRequested(ID,CITIZEN,List.of(ID),null,3)),
            invalid("paymentPlanRequested con total cero",()->planRequested(ID,CITIZEN,List.of(ID),BigDecimal.ZERO,3)),
            invalid("paymentPlanRequested sin cuotas",()->planRequested(ID,CITIZEN,List.of(ID),HALF,0)),

            invalid("updatePaymentPlanStatus sin requestId",()->planStatus(null,CITIZEN,"GRANTED",ID,3,HALF,null)),
            invalid("updatePaymentPlanStatus sin ciudadano",()->planStatus(ID,null,"GRANTED",ID,3,HALF,null)),
            invalid("GRANTED sin plan",()->planStatus(ID,CITIZEN,"GRANTED",null,3,HALF,null)),
            invalid("GRANTED sin cuotas",()->planStatus(ID,CITIZEN,"GRANTED",ID,null,HALF,null)),
            invalid("GRANTED con cuotas cero",()->planStatus(ID,CITIZEN,"GRANTED",ID,0,HALF,null)),
            invalid("GRANTED sin total",()->planStatus(ID,CITIZEN,"GRANTED",ID,3,null,null)),
            invalid("GRANTED con total cero",()->planStatus(ID,CITIZEN,"GRANTED",ID,3,BigDecimal.ZERO,null)),
            invalid("GRANTED con motivo",()->planStatus(ID,CITIZEN,"GRANTED",ID,3,HALF,"Motivo")),
            invalid("REJECTED con plan",()->planStatus(ID,CITIZEN,"REJECTED",ID,null,null,"Motivo")),
            invalid("REJECTED con cuotas",()->planStatus(ID,CITIZEN,"REJECTED",null,3,null,"Motivo")),
            invalid("REJECTED con total",()->planStatus(ID,CITIZEN,"REJECTED",null,null,HALF,"Motivo")),
            invalid("REJECTED sin motivo",()->planStatus(ID,CITIZEN,"REJECTED",null,null,null,null)),
            invalid("REJECTED con motivo en blanco",()->planStatus(ID,CITIZEN,"REJECTED",null,null,null," ")),
            invalid("estado de plan no soportado",()->planStatus(ID,CITIZEN,"PENDING",null,null,null,null)),

            invalid("debtOverdue sin deuda",()->overdue(null,CITIZEN,"C",HALF,FROM)),
            invalid("debtOverdue sin ciudadano",()->overdue(ID,null,"C",HALF,FROM)),
            invalid("debtOverdue sin concepto",()->overdue(ID,CITIZEN," ",HALF,FROM)),
            invalid("debtOverdue sin saldo",()->overdue(ID,CITIZEN,"C",null,FROM)),
            invalid("debtOverdue con saldo cero",()->overdue(ID,CITIZEN,"C",BigDecimal.ZERO,FROM)),
            invalid("debtOverdue sin vencimiento",()->overdue(ID,CITIZEN,"C",HALF,null)));
    }

    private static Arguments invalid(String scenario,Runnable construction){return arguments(scenario,construction);}
    private static void exemptionRequested(UUID requestId,UUID citizenId,String concept,String reason,BigDecimal percentage,LocalDate from,LocalDate until){
        new ConfirmedOutboundEvents.ExemptionRequestedPayload(requestId,citizenId,concept,reason,percentage,from,until);
    }
    private static void exemptionStatus(UUID requestId,UUID citizenId,String status,UUID exemptionId,String concept,BigDecimal percentage,LocalDate from,LocalDate until,String reason){
        new ConfirmedOutboundEvents.UpdateExemptionStatusPayload(requestId,citizenId,status,exemptionId,concept,percentage,from,until,reason);
    }
    private static void planRequested(UUID requestId,UUID citizenId,List<UUID> debtIds,BigDecimal total,int installments){
        new ConfirmedOutboundEvents.PaymentPlanRequestedPayload(requestId,citizenId,debtIds,total,installments);
    }
    private static void planStatus(UUID requestId,UUID citizenId,String status,UUID planId,Integer installments,BigDecimal total,String reason){
        new ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload(requestId,citizenId,status,planId,installments,total,reason);
    }
    private static void overdue(UUID debtId,UUID citizenId,String concept,BigDecimal amount,LocalDate dueDate){
        new ConfirmedOutboundEvents.OverdueDebtPayload(debtId,citizenId,concept,amount,dueDate);
    }
}
