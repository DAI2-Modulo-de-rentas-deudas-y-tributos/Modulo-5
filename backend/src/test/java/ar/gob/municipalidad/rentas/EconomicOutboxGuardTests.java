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

    @Test void persistentUuidEconomicIdentifiersCanReachLocalPublisher(){
        OutboxRepository repository=mock(OutboxRepository.class);
        EventPublisher publisher=mock(EventPublisher.class);
        OutboxEvent event=event("paymentRegistered",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));

        new OutboxPublisher(repository,publisher).publishPending();

        assertThat(event.status).isEqualTo(OutboxStatus.PUBLISHED);
        verify(publisher).publish(event.eventType,event.targetModule,event.payload);
    }

    @Test void internalPlatformEventsAreNotSubjectToCoreContractGuard(){
        OutboxRepository repository=mock(OutboxRepository.class);
        EventPublisher publisher=mock(EventPublisher.class);
        OutboxEvent event=event("paymentRegistered","legacy-internal-payment","legacy-internal-debt");
        event.targetModule="PLATFORM";
        when(repository.findPublishable(any(Pageable.class))).thenReturn(List.of(event));

        new OutboxPublisher(repository,publisher).publishPending();

        assertThat(event.status).isEqualTo(OutboxStatus.PUBLISHED);
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
        when(repository.existsByEventTypeAndAggregateTypeAndAggregateIdAndStatus("paymentPlanRequested",event.aggregateType,event.aggregateId,OutboxStatus.PUBLISHED)).thenReturn(false,true);

        OutboxPublisher outboxPublisher=new OutboxPublisher(repository,publisher);outboxPublisher.publishPending();
        assertThat(event.status).isEqualTo(OutboxStatus.PENDING);
        outboxPublisher.publishPending();
        assertThat(event.status).isEqualTo(OutboxStatus.PUBLISHED);
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
