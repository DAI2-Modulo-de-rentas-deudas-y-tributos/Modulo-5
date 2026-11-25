package ar.gob.municipalidad.rentas;

import jakarta.persistence.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Entity @Table(name="citizen_session")
class CitizenSession {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="canonical_citizen_id",nullable=false) public UUID canonicalCitizenId;
    @Column(name="token_hash",nullable=false,unique=true,length=64) public String tokenHash;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="expires_at",nullable=false) public OffsetDateTime expiresAt;
    @Column(name="revoked_at") public OffsetDateTime revokedAt;
    protected CitizenSession() {}
    boolean usable(OffsetDateTime now){return revokedAt==null&&expiresAt.isAfter(now);}
}

interface CitizenSessionRepository extends JpaRepository<CitizenSession,Long> { Optional<CitizenSession> findByTokenHash(String tokenHash); }

/** Sesión propia de M5 para un ciudadano acreditado por M1: rol TAXPAYER sobre su referencia local. */
@Service
class M1CitizenAuthService {
    static final String ROLE="TAXPAYER";
    private static final String BEARER_PREFIX="Bearer ";
    private static final Set<TaxpayerStatus> OPERABLE=EnumSet.of(TaxpayerStatus.ACTIVE,TaxpayerStatus.ACTIVA);
    private static final List<GrantedAuthority> AUTHORITIES=List.of(new SimpleGrantedAuthority("ROLE_"+ROLE));

    record LoginRequest(@NotBlank @Email @Size(max=180) String email,@NotBlank @Size(max=200) String password) {
        @Override public String toString(){return "LoginRequest[<redacted>]";}
    }
    record UserResponse(String displayName,String role,List<String> authorities,Long taxpayerId,String authSource) {}
    record LoginResponse(String token,UserResponse user) {
        @Override public String toString(){return "LoginResponse[<redacted>]";}
    }
    record ResolvedSession(AuthenticatedIdentity identity,List<GrantedAuthority> authorities) {}

    private final ObjectProvider<M1AuthClient> m1;
    private final TaxpayerRepository taxpayers;
    private final CitizenSessionRepository sessions;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final Duration sessionTtl;
    private final SecureRandom random=new SecureRandom();

    M1CitizenAuthService(ObjectProvider<M1AuthClient> m1,TaxpayerRepository taxpayers,CitizenSessionRepository sessions,
            AuditService audit,PlatformTransactionManager transactionManager,
            @Value("${rentas.m1.auth.session-ttl:PT8H}") Duration sessionTtl) {
        if(sessionTtl==null||sessionTtl.isZero()||sessionTtl.isNegative())throw new IllegalStateException("M1_AUTH_SESSION_TTL_INVALID");
        this.m1=m1;this.taxpayers=taxpayers;this.sessions=sessions;this.audit=audit;
        this.transaction=new TransactionTemplate(transactionManager);this.sessionTtl=sessionTtl;
    }

    /** Sin transacción: no se retiene una conexión de la base mientras responde M1. */
    LoginResponse login(LoginRequest request,String clientAddress) {
        M1AuthClient client=m1.getIfAvailable();
        if(client==null)throw new BusinessException("M1_AUTH_DISABLED","El ingreso con Ciudadanos no está habilitado",503);
        M1AuthClient.M1Citizen citizen;
        try { citizen=client.login(request.email().trim(),request.password(),clientAddress); }
        catch(BusinessException ex) { throw rejected(ex,"anonymous"); }
        UUID citizenId=citizen.citizenId();
        TaxpayerReference taxpayer=taxpayers.findByCanonicalCitizenId(citizenId)
            .or(()->registerFromLogin(citizen))
            .filter(t->t.taxpayerType==TaxpayerType.CITIZEN)
            .orElseThrow(()->rejected(new BusinessException("M1_CITIZEN_NOT_LINKED",
                "Tu cuenta de Ciudadanos todavía no está vinculada a Rentas. Consultá en la Dirección de Rentas",403),citizenId.toString()));
        if(!OPERABLE.contains(taxpayer.externalStatus))
            throw rejected(inactive(),citizenId.toString());
        String token=newToken();
        CitizenSession session=new CitizenSession();
        session.taxpayerId=taxpayer.id;session.canonicalCitizenId=citizenId;session.tokenHash=hash(token);
        session.createdAt=OffsetDateTime.now();session.expiresAt=session.createdAt.plus(sessionTtl);
        transaction.executeWithoutResult(status -> {
            sessions.save(session);
            audit.recordSecurityEvent("M1_LOGIN_SUCCEEDED",citizenId.toString(),ROLE,Map.of("outcome","SUCCESS"));
        });
        return new LoginResponse(token,user(taxpayer));
    }

    /**
     * Primer ingreso: crea la referencia del vecino con los datos que devolvió el login de M1 (ADR 0012).
     * No la crea si M1 no mandó DNI, CUIL y nombre válidos, ni si ya hay otra referencia de ciudadano
     * con ese DNI, CUIL o ID externo: esa se vincula a mano con canonical-citizen-link.
     */
    private Optional<TaxpayerReference> registerFromLogin(M1AuthClient.M1Citizen citizen) {
        if(!citizen.registrable())return Optional.empty();
        String externalId=citizen.citizenId().toString();
        try {
            return transaction.execute(status -> {
                if(taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,externalId).isPresent()
                        ||taxpayers.findByTaxpayerTypeAndDni(TaxpayerType.CITIZEN,citizen.dni()).isPresent()
                        ||taxpayers.findByTaxpayerTypeAndCuit(TaxpayerType.CITIZEN,citizen.cuit()).isPresent())
                    return Optional.<TaxpayerReference>empty();
                TaxpayerReference taxpayer=new TaxpayerReference();
                taxpayer.taxpayerType=TaxpayerType.CITIZEN;taxpayer.externalId=externalId;taxpayer.canonicalCitizenId=citizen.citizenId();
                taxpayer.dni=citizen.dni();taxpayer.cuit=citizen.cuit();taxpayer.displayName=citizen.displayName();
                taxpayer.externalStatus=TaxpayerStatus.ACTIVE;taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();
                taxpayers.saveAndFlush(taxpayer);
                audit.recordSecurityEvent("TAXPAYER_REGISTERED_FROM_M1_LOGIN",externalId,ROLE,Map.of("taxpayerId",taxpayer.id));
                return Optional.of(taxpayer);
            });
        } catch(DataIntegrityViolationException concurrentFirstLogin) {
            // Dos primeros ingresos simultáneos: el otro ya la creó.
            return taxpayers.findByCanonicalCitizenId(citizen.citizenId());
        }
    }

    @Transactional(readOnly=true) public UserResponse me(String authorization) {
        return user(requireTaxpayer(requireSession(authorization)));
    }

    @Transactional public void logout(String authorization) {
        CitizenSession session=requireSession(authorization);
        session.revokedAt=OffsetDateTime.now();
        audit.recordSecurityEvent("M1_LOGOUT_SUCCEEDED",session.canonicalCitizenId.toString(),ROLE,Map.of("outcome","SUCCESS"));
    }

    @Transactional(readOnly=true) public ResolvedSession resolve(String authorization) {
        CitizenSession session=requireSession(authorization);
        TaxpayerReference taxpayer=requireTaxpayer(session);
        return new ResolvedSession(new AuthenticatedIdentity("m1:"+session.canonicalCitizenId,taxpayer.id,session.canonicalCitizenId),AUTHORITIES);
    }

    /** Token opaco de {@code Authorization: Bearer}, o null si el encabezado no lo trae. */
    static String bearerToken(String authorization) {
        if(authorization==null||!authorization.regionMatches(true,0,BEARER_PREFIX,0,BEARER_PREFIX.length()))return null;
        String token=authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty()?null:token;
    }

    private CitizenSession requireSession(String authorization) {
        String token=bearerToken(authorization);
        if(token==null)throw unauthenticated();
        CitizenSession session=sessions.findByTokenHash(hash(token)).orElseThrow(M1CitizenAuthService::unauthenticated);
        if(!session.usable(OffsetDateTime.now()))throw unauthenticated();
        return session;
    }

    /** Un bloqueo o fallecimiento informado por M1 corta también las sesiones ya abiertas. */
    private TaxpayerReference requireTaxpayer(CitizenSession session) {
        TaxpayerReference taxpayer=taxpayers.findById(session.taxpayerId).orElseThrow(M1CitizenAuthService::unauthenticated);
        if(!session.canonicalCitizenId.equals(taxpayer.canonicalCitizenId)||!OPERABLE.contains(taxpayer.externalStatus))throw unauthenticated();
        return taxpayer;
    }

    private BusinessException rejected(BusinessException reason,String actorId) {
        audit.recordSecurityEventRequiresNew("M1_LOGIN_REJECTED",actorId,"ANONYMOUS",Map.of("reason",reason.code));
        return reason;
    }

    private static UserResponse user(TaxpayerReference taxpayer) {
        return new UserResponse(taxpayer.displayName,ROLE,List.of(ROLE),taxpayer.id,"M1");
    }

    private static BusinessException inactive() {
        return new BusinessException("M1_TAXPAYER_INACTIVE","Tu cuenta no está habilitada para operar en Rentas",403);
    }

    private static BusinessException unauthenticated() {
        return new BusinessException("UNAUTHENTICATED","Sesión inválida o vencida",401);
    }

    private String newToken() {
        byte[] raw=new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 no disponible",ex); }
    }
}

@RestController
@RequestMapping("/api/v1/auth/m1")
class M1CitizenAuthController {
    static final String LOGIN_PATH="/api/v1/auth/m1/login";
    private final M1CitizenAuthService auth;
    private final TrustedClientAddressResolver clientAddresses;
    M1CitizenAuthController(M1CitizenAuthService auth,TrustedClientAddressResolver clientAddresses){this.auth=auth;this.clientAddresses=clientAddresses;}

    @PostMapping("/login")
    M1CitizenAuthService.LoginResponse login(@Valid @RequestBody M1CitizenAuthService.LoginRequest request,HttpServletRequest servletRequest) {
        return auth.login(request,clientAddresses.resolve(servletRequest));
    }

    @GetMapping("/me")
    M1CitizenAuthService.UserResponse me(@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization) {
        return auth.me(authorization);
    }

    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization) {
        auth.logout(authorization);
    }
}

/** Resuelve {@code Authorization: Bearer} contra las sesiones del personal y las ciudadanas. Vale en todos los perfiles. */
@Component
class CitizenSessionFilter extends OncePerRequestFilter {
    private final M1CitizenAuthService sessions;
    private final StaffAuthService staff;
    private final SecurityErrorResponseWriter errors;
    CitizenSessionFilter(M1CitizenAuthService sessions,StaffAuthService staff,SecurityErrorResponseWriter errors){this.sessions=sessions;this.staff=staff;this.errors=errors;}

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String authorization=request.getHeader(HttpHeaders.AUTHORIZATION);
        if(M1CitizenAuthService.bearerToken(authorization)!=null&&applies(request)
                &&SecurityContextHolder.getContext().getAuthentication()==null) {
            try {
                M1CitizenAuthService.ResolvedSession session=staff.resolve(authorization).orElseGet(()->sessions.resolve(authorization));
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(session.identity(),null,session.authorities()));
            } catch(BusinessException ex) {
                errors.write(request,response,HttpServletResponse.SC_UNAUTHORIZED,"UNAUTHENTICATED","Sesión inválida o vencida");
                return;
            }
        }
        chain.doFilter(request,response);
    }

    private static boolean applies(HttpServletRequest request) {
        String path=request.getRequestURI();
        return path.startsWith("/api/v1/")&&!path.equals("/api/v1/health")
            &&!("POST".equalsIgnoreCase(request.getMethod())
                &&(path.equals(M1CitizenAuthController.LOGIN_PATH)||path.equals(StaffAuthController.LOGIN_PATH)));
    }
}
