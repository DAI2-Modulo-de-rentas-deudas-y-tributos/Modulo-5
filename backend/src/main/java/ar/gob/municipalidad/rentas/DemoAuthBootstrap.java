package ar.gob.municipalidad.rentas;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;

@Component
@ConditionalOnProperty(name="rentas.security.dev-mode",havingValue="true")
class DemoAuthBootstrap implements ApplicationRunner {
    private final DemoUserRepository users;private final TaxpayerRepository taxpayers;private final PasswordEncoder passwords;private final String password;
    DemoAuthBootstrap(DemoUserRepository users,TaxpayerRepository taxpayers,PasswordEncoder passwords,
        @Value("${rentas.security.demo-bootstrap-password:}") String password){this.users=users;this.taxpayers=taxpayers;this.passwords=passwords;this.password=password;}
    @Override @Transactional public void run(ApplicationArguments args){
        if(password==null||password.isBlank())return;
        TaxpayerReference taxpayer=taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,"DEMO-AUTH-TAXPAYER").orElseGet(()->{
            TaxpayerReference x=new TaxpayerReference();x.taxpayerType=TaxpayerType.CITIZEN;x.externalId="DEMO-AUTH-TAXPAYER";x.dni="99999999";
            x.displayName="Contribuyente demo autenticación";x.externalStatus=TaxpayerStatus.ACTIVE;x.createdAt=x.updatedAt=OffsetDateTime.now();return taxpayers.save(x);
        });
        create("demo.rentas","Rentas Demo",DemoRole.RENTAS,null);create("demo.supervisor","Supervisor Demo",DemoRole.SUPERVISOR,null);
        create("demo.caja","Caja Demo",DemoRole.CASHIER,null);create("demo.auditoria","Auditoría Demo",DemoRole.AUDITOR,null);
        create("demo.contribuyente","Contribuyente Demo",DemoRole.TAXPAYER,taxpayer.id);
    }
    private void create(String username,String displayName,DemoRole role,Long taxpayerId){
        if(users.existsByUsernameIgnoreCase(username))return;DemoUser x=new DemoUser();x.username=username;x.passwordHash=passwords.encode(password);
        x.displayName=displayName;x.role=role;x.taxpayerId=taxpayerId;x.active=true;x.createdAt=x.updatedAt=OffsetDateTime.now();users.save(x);
    }
}
