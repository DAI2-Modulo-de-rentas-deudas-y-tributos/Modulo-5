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
