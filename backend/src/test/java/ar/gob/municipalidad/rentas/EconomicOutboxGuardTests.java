package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EconomicOutboxGuardTests {
    @Test void legacyM8EventNameIsDeadLetteredBeforePublisherIsCalled(){
        OutboxRepository repository=mock(OutboxRepository.class);
        EventPublisher publisher=mock(EventPublisher.class);
        OutboxEvent event=event("overdueDebt",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));

        new OutboxPublisher(repository,publisher).publishPending();

        assertThat(event.status).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(event.errorMessage).startsWith("M8_V3_EVENT_NAME_REVIEW_REQUIRED");
        verifyNoInteractions(publisher);
    }

    @Test void oldEconomicIdentifiersAreDeadLetteredBeforePublisherIsCalled(){
        OutboxRepository repository=mock(OutboxRepository.class);
        EventPublisher publisher=mock(EventPublisher.class);
        OutboxEvent event=event("paymentRegistered","payment-12","debt-34");
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));

        new OutboxPublisher(repository,publisher).publishPending();

        assertThat(event.status).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(event.errorMessage).startsWith("ECONOMIC_CONTRACT_REVIEW_REQUIRED");
        verifyNoInteractions(publisher);
    }

    @Test void invalidTrafficOriginIsDeadLetteredBeforePublisherIsCalled(){
        OutboxRepository repository=mock(OutboxRepository.class);
        EventPublisher publisher=mock(EventPublisher.class);
        OutboxEvent event=event("paymentRegistered",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        event.payload="{\"eventId\":\""+event.id+"\",\"eventType\":\"paymentRegistered\",\"eventVersion\":\"1.0\","
            +"\"sourceModule\":\"rentas\",\"correlationId\":\""+UUID.randomUUID()+"\",\"causationId\":null,"
            +"\"data\":{\"paymentId\":\""+UUID.randomUUID()+"\",\"debtId\":\""+UUID.randomUUID()+"\","
            +"\"originType\":\"TRAFFIC_FINE\",\"originId\":\"INF-LEGACY\",\"infractionId\":\"INF-LEGACY\","
            +"\"amountApplied\":10,\"paidAt\":\"2026-10-27T10:00:00-03:00\",\"remainingBalance\":20}}";
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));

        new OutboxPublisher(repository,publisher).publishPending();

        assertThat(event.status).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(event.errorMessage).startsWith("M7_CONTRACT_REVIEW_REQUIRED");
        verifyNoInteractions(publisher);
    }

    @Test void historicalM4OriginIsDeadLetteredBeforePublisherIsCalled(){
        OutboxRepository repository=mock(OutboxRepository.class);EventPublisher publisher=mock(EventPublisher.class);
        OutboxEvent event=event("paymentRegistered",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        event.payload="{\"eventId\":\""+event.id+"\",\"eventType\":\"paymentRegistered\",\"eventVersion\":\"1.0\","
            +"\"sourceModule\":\"rentas\",\"correlationId\":\""+UUID.randomUUID()+"\","
            +"\"data\":{\"paymentId\":\""+UUID.randomUUID()+"\",\"debtId\":\""+UUID.randomUUID()+"\","
            +"\"originType\":\"PERMIT_FEE\",\"originId\":\"FEE-123\",\"amountApplied\":10,"
            +"\"paidAt\":\"2026-10-27T10:00:00-03:00\",\"remainingBalance\":20}}";
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));
        new OutboxPublisher(repository,publisher).publishPending();
        assertThat(event.status).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(event.errorMessage).startsWith("M4_CONTRACT_REVIEW_REQUIRED");verifyNoInteractions(publisher);
    }

    @Test void unapprovedM4SettlementWithoutCitizenIsDeadLettered(){
        OutboxRepository repository=mock(OutboxRepository.class);EventPublisher publisher=mock(EventPublisher.class);
        OutboxEvent event=event("debtSettled",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        event.payload="{\"eventId\":\""+event.id+"\",\"eventType\":\"debtSettled\",\"eventVersion\":\"1.0\","
            +"\"sourceModule\":\"rentas\",\"correlationId\":\""+UUID.randomUUID()+"\","
            +"\"data\":{\"debtId\":\""+UUID.randomUUID()+"\",\"originType\":\"COMMERCIAL_FINE\","
            +"\"originId\":\""+UUID.randomUUID()+"\",\"conceptCode\":\"COMMERCIAL_FINE\","
            +"\"settledAt\":\"2026-10-27T10:00:00-03:00\",\"remainingBalance\":0,\"outstandingBalance\":0}}";
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));
        new OutboxPublisher(repository,publisher).publishPending();
        assertThat(event.status).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(event.errorMessage).startsWith("M4_CONTRACT_REVIEW_REQUIRED");verifyNoInteractions(publisher);
    }

    @Test void persistentUuidEconomicIdentifiersCanReachLocalPublisher(){
        OutboxRepository repository=mock(OutboxRepository.class);
        EventPublisher publisher=mock(EventPublisher.class);
        OutboxEvent event=event("paymentRegistered",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));

        new OutboxPublisher(repository,publisher).publishPending();

        assertThat(event.status).isEqualTo(OutboxStatus.LOCAL_ONLY);
        verify(publisher).publish(event.eventType,event.targetModule,event.payload);
    }

    @Test void internalPlatformEventsAreNotSubjectToCoreContractGuard(){
        OutboxRepository repository=mock(OutboxRepository.class);
        EventPublisher publisher=mock(EventPublisher.class);
        OutboxEvent event=event("paymentRegistered","legacy-internal-payment","legacy-internal-debt");
        event.targetModule="PLATFORM";
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));

        new OutboxPublisher(repository,publisher).publishPending();

        assertThat(event.status).isEqualTo(OutboxStatus.LOCAL_ONLY);
        verifyNoInteractions(publisher);
    }

    @Test void legacyPlanIdentifiersAreDeadLetteredAndNeverPublished(){
        OutboxRepository repository=mock(OutboxRepository.class);EventPublisher publisher=mock(EventPublisher.class);
        OutboxEvent event=event("paymentPlanRequested","ppr-12","debt-34");
        event.payload="{\"data\":{\"requestId\":\"ppr-12\",\"citizenId\":\"citizen-1\",\"debtIds\":[\"debt-34\"],\"totalDebt\":100,\"installments\":3}}";
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));

        new OutboxPublisher(repository,publisher).publishPending();

        assertThat(event.status).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(event.errorMessage).startsWith("M1_PLAN_CONTRACT_REVIEW_REQUIRED");
        verifyNoInteractions(publisher);
    }

    @Test void planResolutionWaitsForRequestToBeLocallyPublished(){
        OutboxRepository repository=mock(OutboxRepository.class);EventPublisher publisher=mock(EventPublisher.class);
        OutboxEvent event=event("updatePaymentPlanStatus","1","1");
        event.payload="{\"data\":{\"requestId\":\"7d6a54b0-5e58-4ed2-a1d1-94bde2fc1d41\",\"citizenId\":\"1bc4530b-27da-4f48-b7b6-03d27a7f0be4\",\"status\":\"REJECTED\",\"reason\":\"No corresponde\"}}";
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));
        when(repository.existsByEventTypeAndAggregateTypeAndAggregateIdAndStatusIn("paymentPlanRequested",event.aggregateType,event.aggregateId,List.of(OutboxStatus.PUBLISHED,OutboxStatus.LOCAL_ONLY,OutboxStatus.BROKER_CONFIRMED))).thenReturn(false,true);

        OutboxPublisher outboxPublisher=new OutboxPublisher(repository,publisher);outboxPublisher.publishPending();
        assertThat(event.status).isEqualTo(OutboxStatus.PENDING);
        outboxPublisher.publishPending();
        assertThat(event.status).isEqualTo(OutboxStatus.LOCAL_ONLY);
        verify(publisher).publish(event.eventType,event.targetModule,event.payload);
    }

    private OutboxEvent event(String type,String paymentId,String debtId){
        OutboxEvent event=new OutboxEvent();
        event.id=UUID.randomUUID();
        event.eventType=type;
        event.targetModule="CORE";
        event.aggregateType="PaymentAllocation";
        event.aggregateId="1";
        event.payload="{\"data\":{\"paymentId\":\""+paymentId+"\",\"debtId\":\""+debtId+"\"}}";
        event.status=OutboxStatus.PENDING;
        event.retryCount=0;
        event.createdAt=OffsetDateTime.now();
        return event;
    }
}
