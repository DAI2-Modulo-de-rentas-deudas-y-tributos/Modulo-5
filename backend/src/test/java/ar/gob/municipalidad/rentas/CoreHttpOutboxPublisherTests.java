package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class CoreHttpOutboxPublisherTests {
    private static final UUID REQUEST=UUID.fromString("4b3ee76d-30f5-44b0-a9d1-a647452d2c50");
    private static final UUID CITIZEN=UUID.fromString("cebe57a0-3865-4cf3-ae6e-497c45f5c7e9");
    private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();

    @Test void coreAcceptanceUsesDistinctDurableStatusAndIsNotRepublished() {
        OutboxRepository repository=mock(OutboxRepository.class);EventPublisher sender=mock(EventPublisher.class);
        OutboxEvent event=event("exemptionRequested",REQUEST.toString(),envelope("exemptionRequested","{\"requestId\":\""+REQUEST+"\",\"citizenId\":\""+CITIZEN+"\"}"));
        when(repository.findPublishableForCoreHttp(anyCollection(),anyCollection(),any(Pageable.class))).thenReturn(List.of(event),List.of());
        when(sender.publish(event.eventType,event.targetModule,event.payload)).thenReturn(OutboxStatus.CORE_ACCEPTED);

        OutboxPublisher publisher=publisher(repository,sender,event);
        publisher.publishPending();publisher.publishPending();

        assertThat(event.status).isEqualTo(OutboxStatus.CORE_ACCEPTED);
        assertThat(event.publishedAt).isNotNull();
        assertThat(event.errorMessage).isNull();
        verify(sender,times(1)).publish(event.eventType,event.targetModule,event.payload);
        verify(repository,times(2)).findPublishableForCoreHttp(Set.of("exemptionRequested"),Set.of(event.id),PageRequest.of(0,1));
        verify(repository,never()).findPublishable(any(Pageable.class));
    }

    @Test void coreHttpOnlyReleasesFollowupAfterCoreAcceptedOriginalRequest() {
        OutboxRepository repository=mock(OutboxRepository.class);EventPublisher sender=mock(EventPublisher.class);
        OutboxEvent update=event("updateExemptionStatus",REQUEST.toString(),envelope("updateExemptionStatus","{\"requestId\":\""+REQUEST+"\",\"citizenId\":\""+CITIZEN+"\",\"status\":\"REJECTED\"}"));
        when(repository.findPublishableForCoreHttp(anyCollection(),anyCollection(),any(Pageable.class))).thenReturn(List.of(update));
        when(repository.existsByEventTypeAndAggregateTypeAndAggregateIdAndStatusIn("exemptionRequested",update.aggregateType,
            update.aggregateId,List.of(OutboxStatus.CORE_ACCEPTED))).thenReturn(true);
        when(sender.publish(update.eventType,update.targetModule,update.payload)).thenReturn(OutboxStatus.CORE_ACCEPTED);

        publisher(repository,sender,update).publishPending();

        assertThat(update.status).isEqualTo(OutboxStatus.CORE_ACCEPTED);
        verify(repository).existsByEventTypeAndAggregateTypeAndAggregateIdAndStatusIn("exemptionRequested",update.aggregateType,
            update.aggregateId,List.of(OutboxStatus.CORE_ACCEPTED));
        verify(sender).publish(update.eventType,update.targetModule,update.payload);
    }

    @Test void paymentPlanStatusWaitsForCoreAcceptanceOfRequest() {
        OutboxRepository repository=mock(OutboxRepository.class);EventPublisher sender=mock(EventPublisher.class);
        OutboxEvent update=event("updatePaymentPlanStatus",REQUEST.toString(),envelope("updatePaymentPlanStatus",
            "{\"requestId\":\""+REQUEST+"\",\"citizenId\":\""+CITIZEN+"\",\"status\":\"REJECTED\",\"reason\":\"No elegible\"}"));
        when(repository.findPublishableForCoreHttp(anyCollection(),anyCollection(),any(Pageable.class))).thenReturn(List.of(update));
        when(repository.existsByEventTypeAndAggregateTypeAndAggregateIdAndStatusIn("paymentPlanRequested",update.aggregateType,
            update.aggregateId,List.of(OutboxStatus.CORE_ACCEPTED))).thenReturn(false);

        new OutboxPublisher(repository,sender,"core-http",update.eventType,update.id.toString()).publishPending();

        verify(repository).existsByEventTypeAndAggregateTypeAndAggregateIdAndStatusIn("paymentPlanRequested",update.aggregateType,
            update.aggregateId,List.of(OutboxStatus.CORE_ACCEPTED));
        verifyNoInteractions(sender);
        assertThat(update.status).isEqualTo(OutboxStatus.PENDING);
    }

    @Test void localLogRowCanBePromotedOnlyWhenItsExactIdIsAuthorized() {
        OutboxRepository repository=mock(OutboxRepository.class);OutboxEvent event=event("exemptionRequested",REQUEST.toString(),requestPayload());
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));
        OutboxPublisher local=new OutboxPublisher(repository,(type,target,body)->OutboxStatus.LOCAL_ONLY);
        local.publishPending();
        assertThat(event.status).isEqualTo(OutboxStatus.LOCAL_ONLY);

        when(repository.findPublishableForCoreHttp(anyCollection(),anyCollection(),any(Pageable.class))).thenReturn(List.of(event));
        OutboxPublisher core=new OutboxPublisher(repository,(type,target,body)->OutboxStatus.CORE_ACCEPTED,
            "core-http","exemptionRequested",event.id.toString());
        core.publishPending();

        assertThat(event.status).isEqualTo(OutboxStatus.CORE_ACCEPTED);
        verify(repository).findPublishableForCoreHttp(Set.of("exemptionRequested"),Set.of(event.id),PageRequest.of(0,1));
    }

    @Test void unapprovedHistoricalRowsAreNotPublishedOrChanged() {
        OutboxRepository repository=mock(OutboxRepository.class);EventPublisher sender=mock(EventPublisher.class);
        OutboxEvent unrelated=event("paymentRegistered",REQUEST.toString(),envelope("paymentRegistered","{}"));
        unrelated.status=OutboxStatus.LOCAL_ONLY;
        when(repository.findPublishableForCoreHttp(anyCollection(),anyCollection(),any(Pageable.class))).thenReturn(List.of(unrelated));

        new OutboxPublisher(repository,sender,"core-http","exemptionRequested",unrelated.id.toString()).publishPending();

        verify(repository).findPublishableForCoreHttp(Set.of("exemptionRequested"),Set.of(unrelated.id),PageRequest.of(0,1));
        verifyNoInteractions(sender);
        assertThat(unrelated.status).isEqualTo(OutboxStatus.LOCAL_ONLY);
        assertThat(unrelated.payload).contains(unrelated.id.toString());
    }

    @Test void coreHttpRequiresNonEmptyTypeAndEventIdAllowlist() {
        OutboxRepository repository=mock(OutboxRepository.class);EventPublisher sender=mock(EventPublisher.class);
        assertThatThrownBy(()->new OutboxPublisher(repository,sender,"core-http","",""))
            .isInstanceOf(IllegalStateException.class).hasMessage("CORE_HTTP_EVENT_ALLOWLIST_REQUIRED");
        assertThatThrownBy(()->new OutboxPublisher(repository,sender,"core-http","exemptionRequested","not-a-uuid"))
            .isInstanceOf(IllegalStateException.class).hasMessage("CORE_HTTP_EVENT_ALLOWLIST_INVALID");
    }

    @Test void permanentValidationFailureKeepsPayloadAndMovesToDeadLetter() {
        OutboxRepository repository=mock(OutboxRepository.class);String payload=requestPayload();OutboxEvent event=event("exemptionRequested",REQUEST.toString(),payload);
        when(repository.findPublishableForCoreHttp(anyCollection(),anyCollection(),any(Pageable.class))).thenReturn(List.of(event));
        publisher(repository,(type,target,body)->{throw new CoreHttpPublishException("CORE_HTTP_EVENT_VALIDATION_422",false);},event).publishPending();
        assertThat(event.status).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(event.retryCount).isEqualTo(1);
        assertThat(event.errorMessage).isEqualTo("CORE_HTTP_EVENT_VALIDATION_422");
        assertThat(event.payload).isEqualTo(payload);
    }

    @Test void transientCoreFailureUsesExistingBoundedRetryPolicyAndKeepsEventIdentity() {
        OutboxRepository repository=mock(OutboxRepository.class);String payload=requestPayload();
        OutboxEvent event=event("exemptionRequested",REQUEST.toString(),payload);event.retryCount=3;
        when(repository.findPublishableForCoreHttp(anyCollection(),anyCollection(),any(Pageable.class))).thenReturn(List.of(event));
        publisher(repository,(type,target,body)->{assertThat(body).isEqualTo(payload);throw new CoreHttpPublishException("CORE_HTTP_EVENT_RETRYABLE_503",true);},event).publishPending();
        assertThat(event.status).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.retryCount).isEqualTo(4);
        assertThat(event.payload).isEqualTo(payload);
        assertThat(event.errorMessage).isEqualTo("CORE_HTTP_EVENT_RETRYABLE_503");
        event.retryCount=4;event.status=OutboxStatus.FAILED;
        publisher(repository,(type,target,body)->{throw new CoreHttpPublishException("CORE_HTTP_EVENT_RETRYABLE_503",true);},event).publishPending();
        assertThat(event.retryCount).isEqualTo(5);
        assertThat(event.status).isEqualTo(OutboxStatus.DEAD_LETTER);
    }

    private OutboxPublisher publisher(OutboxRepository repository,EventPublisher sender,OutboxEvent event){
        return new OutboxPublisher(repository,sender,"core-http",event.eventType,event.id.toString());
    }
    private OutboxEvent event(String eventType,String aggregateId,String payload){
        OutboxEvent event=new OutboxEvent();event.id=UUID.fromString(jsonId(payload));event.eventType=eventType;event.targetModule="CORE";
        event.aggregateType="ExemptionRequest";event.aggregateId=aggregateId;event.payload=payload;event.status=OutboxStatus.PENDING;
        event.retryCount=0;event.createdAt=OffsetDateTime.now();return event;
    }
    private String requestPayload(){return envelope("exemptionRequested","{\"requestId\":\""+REQUEST+"\",\"citizenId\":\""+CITIZEN+"\"}");}
    private String envelope(String type,String data){return "{\"eventId\":\""+UUID.randomUUID()+"\",\"eventType\":\""+type+"\",\"eventVersion\":\"1.0\",\"occurredAt\":\"2026-10-31T12:30:00-03:00\",\"sourceModule\":\"rentas\",\"correlationId\":\""+UUID.randomUUID()+"\",\"causationId\":null,\"data\":"+data+"}";}
    private String jsonId(String payload){try{return json.readTree(payload).path("eventId").asText();}catch(Exception ex){throw new IllegalArgumentException(ex);}}
}
