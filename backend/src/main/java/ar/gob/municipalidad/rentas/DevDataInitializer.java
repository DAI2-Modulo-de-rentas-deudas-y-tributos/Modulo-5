package ar.gob.municipalidad.rentas;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;

@Component @Profile("dev")
class DevDataInitializer implements CommandLineRunner {
    private final TaxpayerRepository taxpayers; private final TaxConceptRepository concepts;
    DevDataInitializer(TaxpayerRepository taxpayers,TaxConceptRepository concepts){this.taxpayers=taxpayers;this.concepts=concepts;}
    public void run(String... args){
        taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,"DEV-001").orElseGet(()->{TaxpayerReference t=new TaxpayerReference();t.taxpayerType=TaxpayerType.CITIZEN;t.externalId="DEV-001";t.dni="00000000";t.displayName="Contribuyente de desarrollo";t.externalStatus=TaxpayerStatus.ACTIVE;t.createdAt=t.updatedAt=OffsetDateTime.now();return taxpayers.save(t);});
        concepts.findByCode("TRAFFIC_INFRACTION").orElseGet(()->{TaxConcept c=new TaxConcept();c.code="TRAFFIC_INFRACTION";c.name="Infracción de tránsito";c.type=TaxConceptType.FINE;c.originModule="M7";c.active=true;c.createdAt=c.updatedAt=OffsetDateTime.now();return concepts.save(c);});
    }
}
