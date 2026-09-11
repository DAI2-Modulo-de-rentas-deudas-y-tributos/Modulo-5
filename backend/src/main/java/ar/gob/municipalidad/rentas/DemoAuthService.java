package ar.gob.municipalidad.rentas;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
@ConditionalOnProperty(name="rentas.security.dev-mode",havingValue="true")
class DemoAuthService {
    static final String SESSION_HEADER="X-Demo-Session";
    static final String BOOTSTRAP_HEADER="X-Demo-Bootstrap-Secret";

    private final DemoUserRepository users;
    private final DemoBootstrapLockRepository bootstrapLock;
    private final DemoAuthSessionRepository sessions;
    private final TaxpayerRepository taxpayers;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final String bootstrapSecret;
    private final Duration sessionTtl;
    private final SecureRandom random=new SecureRandom();

    DemoAuthService(DemoUserRepository users,DemoBootstrapLockRepository bootstrapLock,DemoAuthSessionRepository sessions,TaxpayerRepository taxpayers,
            PasswordEncoder passwords,AuditService audit,
            @Value("${rentas.security.demo-bootstrap-password:}") String bootstrapSecret,
            @Value("${rentas.security.demo-session-ttl:PT8H}") Duration sessionTtl) {
        this.users=users;this.bootstrapLock=bootstrapLock;this.sessions=sessions;this.taxpayers=taxpayers;this.passwords=passwords;this.audit=audit;
        this.bootstrapSecret=bootstrapSecret==null?"":bootstrapSecret;
        this.sessionTtl=sessionTtl;
    }

    @Transactional DemoAuthController.LoginResponse login(DemoAuthController.LoginRequest request) {
        DemoUser user=users.findByUsernameIgnoreCase(normalize(request.username()))
            .filter(x->x.active&&passwords.matches(request.password(),x.passwordHash))
            .orElseThrow(()->new BusinessException("INVALID_CREDENTIALS","Usuario o contraseña incorrectos",401));
        String token=newToken();
        DemoAuthSession session=new DemoAuthSession();
        session.demoUserId=user.id;
        session.tokenHash=hashToken(token);
        session.createdAt=OffsetDateTime.now();
        session.expiresAt=session.createdAt.plus(sessionTtl);
        sessions.save(session);
        return new DemoAuthController.LoginResponse(token,response(user));
    }

    @Transactional void logout(String rawToken) {
        DemoAuthSession session=requireSession(rawToken);
        session.revokedAt=OffsetDateTime.now();
    }

    DemoAuthController.UserResponse me(String rawToken) {
        return response(requireUser(requireSession(rawToken)));
    }

    ResolvedSession resolve(String rawToken) {
        DemoUser user=requireUser(requireSession(rawToken));
        return new ResolvedSession(identity(user),authorities(user));
    }

    record ResolvedSession(AuthenticatedIdentity identity,List<org.springframework.security.core.GrantedAuthority> authorities) {}

    List<org.springframework.security.core.GrantedAuthority> authorities(DemoUser user) {
        return response(user).authorities().stream()
            .map(role->(org.springframework.security.core.GrantedAuthority)new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role))
            .toList();
    }

    @Transactional DemoAuthController.UserResponse bootstrap(String secret,DemoAuthController.BootstrapRequest request) {
        if(bootstrapSecret.isBlank()) throw new BusinessException("DEMO_BOOTSTRAP_DISABLED","El bootstrap DEMO no está configurado",403);
        if(secret==null||!constantEquals(secret,bootstrapSecret)) throw new BusinessException("INVALID_BOOTSTRAP_SECRET","Secreto de bootstrap inválido",401);
        if(bootstrapLock.lockBootstrap()==null) throw new IllegalStateException("Falta la fila de control del bootstrap DEMO");
        if(users.count()>0) throw new BusinessException("DEMO_BOOTSTRAP_ALREADY_COMPLETED","Ya existe un usuario DEMO",409);
        DemoUser user=new DemoUser();
        user.username=normalize(request.username());
        user.passwordHash=passwords.encode(request.password());
        user.displayName=request.displayName().trim();
        user.role=DemoRole.SUPERVISOR;
        user.active=true;
        user.createdAt=user.updatedAt=OffsetDateTime.now();
        users.save(user);
        audit.record("DemoUser",user.id,"DEMO_BOOTSTRAP_COMPLETED",response(user));
        return response(user);
    }

    @Transactional DemoAuthController.UserResponse create(DemoAuthController.CreateUserRequest request) {
        String username=normalize(request.username());
        CatalogService.require(!users.existsByUsernameIgnoreCase(username),"DEMO_USERNAME_ALREADY_EXISTS","El usuario demo ya existe");
        if(request.role()==DemoRole.TAXPAYER) {
            CatalogService.require(request.taxpayerId()!=null,"DEMO_TAXPAYER_REQUIRED","El rol TAXPAYER requiere taxpayerId");
            taxpayers.findById(request.taxpayerId()).orElseThrow(()->CatalogService.notFound("Contribuyente"));
        } else CatalogService.require(request.taxpayerId()==null,"DEMO_TAXPAYER_NOT_ALLOWED","Sólo TAXPAYER puede vincular un taxpayerId");
        DemoUser user=new DemoUser();user.username=username;user.passwordHash=passwords.encode(request.password());
        user.displayName=request.displayName().trim();user.role=request.role();user.taxpayerId=request.taxpayerId();user.active=true;
        user.createdAt=user.updatedAt=OffsetDateTime.now();users.save(user);audit.record("DemoUser",user.id,"DEMO_USER_CREATED",response(user));
        return response(user);
    }

    List<DemoAuthController.UserResponse> list(){return users.findAll().stream().map(this::response).toList();}

    static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 no disponible",ex); }
    }

    static AuthenticatedIdentity identity(DemoUser user){return new AuthenticatedIdentity(user.username,user.taxpayerId);}

    DemoAuthController.UserResponse response(DemoUser user){
        List<String> authorities=user.role==DemoRole.SUPERVISOR?List.of("RENTAS","SUPERVISOR"):List.of(user.role.name());
        return new DemoAuthController.UserResponse(user.id,user.username,user.displayName,user.role,authorities,user.taxpayerId,user.active,user.createdAt);
    }

    private DemoAuthSession requireSession(String rawToken) {
        if(rawToken==null||rawToken.isBlank()) throw new BusinessException("UNAUTHENTICATED","Se requiere autenticación",401);
        DemoAuthSession session=sessions.findByTokenHash(hashToken(rawToken.trim()))
            .orElseThrow(()->new BusinessException("UNAUTHENTICATED","Sesión DEMO inválida",401));
        if(!session.usable(OffsetDateTime.now())) throw new BusinessException("UNAUTHENTICATED","Sesión DEMO inválida",401);
        return session;
    }

    private DemoUser requireUser(DemoAuthSession session) {
        DemoUser user=users.findById(session.demoUserId).orElseThrow(()->new BusinessException("UNAUTHENTICATED","Sesión DEMO inválida",401));
        if(!user.active) throw new BusinessException("UNAUTHENTICATED","Sesión DEMO inválida",401);
        return user;
    }

    private String newToken() {
        byte[] raw=new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private String normalize(String username){return username.trim().toLowerCase(java.util.Locale.ROOT);}

    private static boolean constantEquals(String left,String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),right.getBytes(StandardCharsets.UTF_8));
    }
}
