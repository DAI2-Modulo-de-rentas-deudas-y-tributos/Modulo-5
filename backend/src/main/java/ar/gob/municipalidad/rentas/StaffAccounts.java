package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Entity @Table(name="staff_account")
class StaffAccount {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false,unique=true) public String username;
    @Column(name="password_hash",nullable=false) public String passwordHash;
    @Column(name="display_name",nullable=false) public String displayName;
    @Column(nullable=false) public String role;
    @Column(name="integration_actor_id",nullable=false,unique=true) public UUID integrationActorId;
    @Column(nullable=false) public boolean active;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) public OffsetDateTime updatedAt;
}

@Entity @Table(name="staff_session")
class StaffSession {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="staff_account_id",nullable=false) public Long staffAccountId;
    @Column(name="token_hash",nullable=false,unique=true,length=64) public String tokenHash;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="expires_at",nullable=false) public OffsetDateTime expiresAt;
    @Column(name="revoked_at") public OffsetDateTime revokedAt;
}

interface StaffAccountRepository extends JpaRepository<StaffAccount,Long> { Optional<StaffAccount> findByUsername(String username); }

interface StaffSessionRepository extends JpaRepository<StaffSession,Long> {
    Optional<StaffSession> findByTokenHash(String tokenHash);
    @Modifying @Query("update StaffSession s set s.revokedAt=:now where s.staffAccountId=:accountId and s.revokedAt is null")
    int revokeAll(Long accountId,OffsetDateTime now);
}

/**
 * Cuentas propias del personal de Rentas (ADR 0013). Valen en todos los perfiles, incluido prod.
 * Se cargan desde {@code STAFF_ACCOUNTS} (JSON con hashes BCrypt, nunca contraseñas): las que no
 * figuran quedan inactivas. La sesión es un token opaco de {@code Authorization: Bearer}.
 */
@Service
class StaffAuthService {
    static final Set<String> ROLES=Set.of("RENTAS","SUPERVISOR","CASHIER","AUDITOR");
    private static final Logger log=LoggerFactory.getLogger(StaffAuthService.class);
    private static final Pattern USERNAME=Pattern.compile("[a-z0-9._-]{3,60}");
    private static final Pattern BCRYPT=Pattern.compile("\\$2[aby]\\$(1[0-4])\\$[./A-Za-z0-9]{53}");
    private static final int MAX_USER_FAILURES=5,MAX_ORIGIN_FAILURES=20;
    private static final Duration LOCK=Duration.ofMinutes(15);

    record LoginRequest(@NotBlank @Size(max=60) String username,@NotBlank @Size(max=200) String password) {
        @Override public String toString(){return "StaffLoginRequest[<redacted>]";}
    }
    record UserResponse(Long id,String username,String displayName,String role,List<String> authorities,Long taxpayerId,
                        boolean active,OffsetDateTime createdAt,String authSource) {}
    record LoginResponse(String token,UserResponse user) {
        @Override public String toString(){return "StaffLoginResponse[<redacted>]";}
    }
    record ProvisionedAccount(String username,String displayName,String role,String passwordHash) {
        @Override public String toString(){return "ProvisionedAccount["+username+"]";}
    }
    private record Failures(int count,Instant until) {}

    private final StaffAccountRepository accounts;
    private final StaffSessionRepository sessions;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final ObjectMapper json;
    private final String provisioned;
    private final Duration sessionTtl;
    private final String unknownUserHash;
    private final SecureRandom random=new SecureRandom();
    // ponytail: contador en memoria de la única tarea de ECS; si el servicio escala a más tareas, pasarlo a PostgreSQL.
    private final Map<String,Failures> failures=new ConcurrentHashMap<>();

    StaffAuthService(StaffAccountRepository accounts,StaffSessionRepository sessions,PasswordEncoder passwords,AuditService audit,ObjectMapper json,
            @Value("${rentas.staff.accounts:}") String provisioned,@Value("${rentas.staff.session-ttl:PT8H}") Duration sessionTtl) {
        if(sessionTtl==null||sessionTtl.isZero()||sessionTtl.isNegative())throw new IllegalStateException("STAFF_SESSION_TTL_INVALID");
        this.accounts=accounts;this.sessions=sessions;this.passwords=passwords;this.audit=audit;this.json=json;
        this.provisioned=provisioned==null?"":provisioned.trim();this.sessionTtl=sessionTtl;
        this.unknownUserHash=passwords.encode(newToken());
    }

    /** Sincroniza las cuentas con STAFF_ACCOUNTS. Una configuración inválida corta el arranque. */
    @EventListener(ApplicationReadyEvent.class) @Transactional public void provision() {
        if(provisioned.isEmpty())return;
        List<ProvisionedAccount> wanted=parse(provisioned);
        OffsetDateTime now=OffsetDateTime.now();
        Set<String> usernames=new HashSet<>();
        for(ProvisionedAccount p:wanted) {
            String username=p.username().trim().toLowerCase(Locale.ROOT);
            usernames.add(username);
            StaffAccount account=accounts.findByUsername(username).orElseGet(()->{
                StaffAccount created=new StaffAccount();
                created.username=username;created.integrationActorId=UUID.randomUUID();created.createdAt=now;
                return created;
            });
            boolean passwordChanged=account.passwordHash!=null&&!account.passwordHash.equals(p.passwordHash());
            boolean changed=account.id==null||passwordChanged||!account.active||!p.role().equals(account.role)||!p.displayName().trim().equals(account.displayName);
            if(!changed)continue;
            account.passwordHash=p.passwordHash();account.displayName=p.displayName().trim();account.role=p.role();
            account.active=true;account.updatedAt=now;
            accounts.save(account);
            if(passwordChanged)sessions.revokeAll(account.id,now);
            audit.recordSecurityEvent("STAFF_ACCOUNT_PROVISIONED",username,"SYSTEM",Map.of("role",account.role));
        }
        for(StaffAccount account:accounts.findAll()) {
            if(!account.active||usernames.contains(account.username))continue;
            account.active=false;account.updatedAt=now;
            sessions.revokeAll(account.id,now);
            audit.recordSecurityEvent("STAFF_ACCOUNT_DISABLED",account.username,"SYSTEM",Map.of("role",account.role));
        }
        log.info("STAFF_ACCOUNTS sincronizadas: {} activas",usernames.size());
    }

    List<ProvisionedAccount> parse(String raw) {
        List<ProvisionedAccount> list;
        try { list=json.readValue(raw,new TypeReference<List<ProvisionedAccount>>(){}); }
        catch(Exception ex) { throw new IllegalStateException("STAFF_ACCOUNTS_INVALID: no es una lista JSON"); }
        Set<String> seen=new HashSet<>();
        for(ProvisionedAccount p:list) {
            String username=p==null||p.username()==null?"":p.username().trim().toLowerCase(Locale.ROOT);
            if(!USERNAME.matcher(username).matches()||!seen.add(username)
                ||p.displayName()==null||p.displayName().isBlank()||p.displayName().trim().length()>160
                ||!ROLES.contains(p.role())||p.passwordHash()==null||!BCRYPT.matcher(p.passwordHash()).matches())
                throw new IllegalStateException("STAFF_ACCOUNTS_INVALID: revisar la cuenta "+(username.isEmpty()?"sin usuario":username));
        }
        return list;
    }

    @Transactional LoginResponse login(LoginRequest request,String clientAddress) {
        String username=request.username().trim().toLowerCase(Locale.ROOT);
        String userKey="user:"+username,originKey="origin:"+Objects.toString(clientAddress,"unknown");
        int retryAfter=Math.max(retryAfter(userKey),retryAfter(originKey));
        if(retryAfter>0)throw rateLimited(retryAfter);
        StaffAccount account=accounts.findByUsername(username).filter(a->a.active).orElse(null);
        // Siempre se compara un hash BCrypt, exista o no la cuenta, para no revelar usuarios por tiempo.
        boolean matches=passwords.matches(request.password(),account!=null?account.passwordHash:unknownUserHash);
        if(account==null||!matches) {
            fail(userKey,MAX_USER_FAILURES);fail(originKey,MAX_ORIGIN_FAILURES);
            audit.recordSecurityEventRequiresNew("STAFF_LOGIN_FAILED",account!=null?account.username:"anonymous",
                account!=null?account.role:"ANONYMOUS",Map.of("reason","INVALID_CREDENTIALS"));
            throw new BusinessException("INVALID_CREDENTIALS","Usuario o contraseña incorrectos",401);
        }
        failures.remove(userKey);
        String token=newToken();
        StaffSession session=new StaffSession();
        session.staffAccountId=account.id;session.tokenHash=DemoAuthService.hashToken(token);
        session.createdAt=OffsetDateTime.now();session.expiresAt=session.createdAt.plus(sessionTtl);
        sessions.save(session);
        audit.recordSecurityEvent("STAFF_LOGIN_SUCCEEDED",account.username,account.role,Map.of("outcome","SUCCESS"));
        return new LoginResponse(token,user(account));
    }

    @Transactional(readOnly=true) UserResponse me(String authorization) {
        return user(find(authorization).orElseThrow(StaffAuthService::unauthenticated));
    }

    @Transactional void logout(String authorization) {
        String token=M1CitizenAuthService.bearerToken(authorization);
        StaffSession session=token==null?null:sessions.findByTokenHash(DemoAuthService.hashToken(token)).orElse(null);
        StaffAccount account=session==null?null:usable(session);
        if(account==null)throw unauthenticated();
        session.revokedAt=OffsetDateTime.now();
        audit.recordSecurityEvent("STAFF_LOGOUT_SUCCEEDED",account.username,account.role,Map.of("outcome","SUCCESS"));
    }

    /** Sesión del personal para el filtro Bearer; vacío si el token no es de una sesión vigente del personal. */
    @Transactional(readOnly=true) Optional<M1CitizenAuthService.ResolvedSession> resolve(String authorization) {
        return find(authorization).map(account->new M1CitizenAuthService.ResolvedSession(
            new AuthenticatedIdentity(account.username,null,account.integrationActorId),
            authorities(account).stream().map(role->(GrantedAuthority)new SimpleGrantedAuthority("ROLE_"+role)).toList()));
    }

    private Optional<StaffAccount> find(String authorization) {
        String token=M1CitizenAuthService.bearerToken(authorization);
        if(token==null)return Optional.empty();
        return sessions.findByTokenHash(DemoAuthService.hashToken(token)).map(this::usable);
    }

    private StaffAccount usable(StaffSession session) {
        if(session.revokedAt!=null||!session.expiresAt.isAfter(OffsetDateTime.now()))return null;
        return accounts.findById(session.staffAccountId).filter(a->a.active).orElse(null);
    }

    private int retryAfter(String key) {
        Failures f=failures.get(key);
        if(f==null||f.until()==null)return 0;
        long seconds=Duration.between(Instant.now(),f.until()).toSeconds();
        if(seconds<=0){failures.remove(key);return 0;}
        return (int)Math.max(1,seconds);
    }

    private void fail(String key,int max) {
        failures.compute(key,(k,f)->{
            int count=(f==null||(f.until()!=null&&f.until().isBefore(Instant.now())))?1:f.count()+1;
            return new Failures(count,count>=max?Instant.now().plus(LOCK):null);
        });
    }

    private BusinessException rateLimited(int retryAfter) {
        audit.recordSecurityEventRequiresNew("STAFF_LOGIN_RATE_LIMITED","anonymous","ANONYMOUS",Map.of("reason","STAFF_LOGIN_RATE_LIMITED"));
        return new BusinessException("STAFF_LOGIN_RATE_LIMITED","Demasiados intentos. Reintentá cuando venza el bloqueo",429,retryAfter);
    }

    private static List<String> authorities(StaffAccount account) {
        return "SUPERVISOR".equals(account.role)?List.of("RENTAS","SUPERVISOR"):List.of(account.role);
    }

    private static UserResponse user(StaffAccount account) {
        return new UserResponse(account.id,account.username,account.displayName,account.role,authorities(account),null,account.active,account.createdAt,"STAFF");
    }

    private static BusinessException unauthenticated() {
        return new BusinessException("UNAUTHENTICATED","Sesión inválida o vencida",401);
    }

    private String newToken() {
        byte[] raw=new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}

@RestController
@RequestMapping("/api/v1/auth/staff")
class StaffAuthController {
    static final String LOGIN_PATH="/api/v1/auth/staff/login";
    private final StaffAuthService auth;
    private final TrustedClientAddressResolver clientAddresses;
    StaffAuthController(StaffAuthService auth,TrustedClientAddressResolver clientAddresses){this.auth=auth;this.clientAddresses=clientAddresses;}

    @PostMapping("/login")
    StaffAuthService.LoginResponse login(@Valid @RequestBody StaffAuthService.LoginRequest request,HttpServletRequest servletRequest) {
        return auth.login(request,clientAddresses.resolve(servletRequest));
    }

    @GetMapping("/me")
    StaffAuthService.UserResponse me(@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization) {
        return auth.me(authorization);
    }

    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization) {
        auth.logout(authorization);
    }
}
