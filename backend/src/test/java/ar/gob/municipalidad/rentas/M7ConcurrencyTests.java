package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test") @SpringBootTest @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class M7ConcurrencyTests {
    @Autowired ConfirmedM7Consumer m7;@Autowired TaxpayerRepository taxpayers;@Autowired TaxConceptRepository concepts;
    @Autowired ExternalObligationRepository obligations;@Autowired DebtRepository debts;

    @Test void concurrentEventsForSameInfractionCannotDuplicateDebt() throws Exception {
        String dni=String.valueOf(Math.abs(System.nanoTime()));TaxpayerReference taxpayer=new TaxpayerReference();taxpayer.taxpayerType=TaxpayerType.CITIZEN;taxpayer.externalId="M1-"+dni;taxpayer.dni=dni;taxpayer.displayName="Concurrente";taxpayer.externalStatus=TaxpayerStatus.ACTIVE;taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();taxpayers.save(taxpayer);
        concepts.findByCode("TRAFFIC_INFRACTION").orElseGet(()->{TaxConcept c=new TaxConcept();c.code="TRAFFIC_INFRACTION";c.name="Infracción";c.type=TaxConceptType.FINE;c.originModule="M7";c.active=true;c.createdAt=c.updatedAt=OffsetDateTime.now();return concepts.save(c);});
        UUID infraction=UUID.randomUUID();var data=new ConfirmedInboundEvents.M7InfractionData(infraction,dni,ConfirmedInboundEvents.M7DebtorIdType.DNI,"AA111AA","TEST",OffsetDateTime.now(),new BigDecimal("50"),List.of(),new BigDecimal("75"),"INS-1",new ConfirmedInboundEvents.M7Location("Calle","Esquina",new BigDecimal("-34.60"),new BigDecimal("-58.40")));
        CountDownLatch start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{List<Future<ExternalObligation>> results=List.of(pool.submit(task(start,data)),pool.submit(task(start,data)));start.countDown();for(Future<ExternalObligation> result:results)try{result.get(5,TimeUnit.SECONDS);}catch(ExecutionException ignored){}
        }finally{pool.shutdownNow();}
        ExternalObligation stored=obligations.findBySourceModuleAndExternalTypeAndExternalReferenceId("M7",ExternalObligationType.TRAFFIC_INFRACTION,infraction.toString()).orElseThrow();
        assertThat(debts.existsByExternalObligationId(stored.id)).isTrue();assertThat(debts.findByTaxpayerId(taxpayer.id)).hasSize(1);
    }

    private Callable<ExternalObligation> task(CountDownLatch start,ConfirmedInboundEvents.M7InfractionData data){return ()->{start.await();return m7.consume(new ConfirmedInboundEvents.M7InfractionConfirmedEvent(UUID.randomUUID(),"infractionConfirmed",OffsetDateTime.now(),"transito",data));};}
}
