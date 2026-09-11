package ar.gob.municipalidad.rentas;

import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sesiones DEMO reales para MockMvc: identidad y roles salen de PostgreSQL/H2, no de X-Dev-*. */
class DemoAuthSessions {
    static final String PASSWORD="test-pass-ok";
    private final DemoUserRepository users;
    private final TaxpayerRepository taxpayers;
    private final PasswordEncoder encoder;
    private final DemoAuthService auth;
    private final Map<String,String> tokens=new ConcurrentHashMap<>();

    DemoAuthSessions(DemoUserRepository users,TaxpayerRepository taxpayers,PasswordEncoder encoder,DemoAuthService auth) {
        this.users=users;this.taxpayers=taxpayers;this.encoder=encoder;this.auth=auth;
    }

    String token(DemoRole role){return token(role,null);}

    String token(DemoRole role,Long taxpayerId) {
        String key=role.name()+":"+(taxpayerId==null?"_":taxpayerId);
        return tokens.computeIfAbsent(key,ignored->open(role,taxpayerId));
    }

    private String open(DemoRole role,Long taxpayerId) {
        Long linked=taxpayerId;
        if(role==DemoRole.TAXPAYER&&linked==null) {
            TaxpayerReference taxpayer=new TaxpayerReference();
            taxpayer.taxpayerType=TaxpayerType.CITIZEN;
            taxpayer.externalId="SESSION-"+UUID.randomUUID();
            taxpayer.dni="10000000";
            taxpayer.displayName="Contribuyente sesión";
            taxpayer.externalStatus=TaxpayerStatus.ACTIVE;
            taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();
            linked=taxpayers.save(taxpayer).id;
        }
        DemoUser user=new DemoUser();
        user.username=("mvc."+role.name()+"."+UUID.randomUUID()).toLowerCase();
        user.passwordHash=encoder.encode(PASSWORD);
        user.displayName="Usuario "+role.name();
        user.role=role;
        user.taxpayerId=role==DemoRole.TAXPAYER?linked:null;
        user.active=true;
        user.createdAt=user.updatedAt=OffsetDateTime.now();
        users.save(user);
        return auth.login(new DemoAuthController.LoginRequest(user.username,PASSWORD)).token();
    }
}
