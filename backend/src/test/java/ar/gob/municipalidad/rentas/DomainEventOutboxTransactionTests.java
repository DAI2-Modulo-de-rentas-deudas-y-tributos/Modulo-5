package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class DomainEventOutboxTransactionTests {
    @Autowired EventosDeDominio eventos;
    @Autowired OutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void internalEventRollsBackWithTheCallingTransaction() {
        Debt debt=new Debt();debt.id=987654321L;
        AdjustmentRequest adjustment=new AdjustmentRequest();adjustment.id=123456789L;

        assertThatThrownBy(()->new TransactionTemplate(transactionManager).execute(status->{
            eventos.deudaAjustada(debt,adjustment);
            throw new IllegalStateException("rollback esperado");
        })).isInstanceOf(IllegalStateException.class).hasMessage("rollback esperado");

        assertThat(outbox.findAll()).noneMatch(event->event.eventType.equals("debtAdjusted")&&event.aggregateId.equals(debt.id.toString()));
    }
}
