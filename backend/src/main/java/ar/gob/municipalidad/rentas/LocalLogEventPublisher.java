package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name="BROKER_ADAPTER",havingValue="local-log",matchIfMissing=true)
class LocalLogEventPublisher implements EventPublisher {
    private static final Logger log=LoggerFactory.getLogger(LocalLogEventPublisher.class);
    public OutboxStatus publish(String eventType,String targetModule,String payload){log.info("BROKER_ADAPTER local-log eventType={} targetModule={}",eventType,targetModule);return OutboxStatus.LOCAL_ONLY;}
}
