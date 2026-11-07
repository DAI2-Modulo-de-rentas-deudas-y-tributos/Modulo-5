package ar.gob.municipalidad.rentas;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exhaustive regression checks for controller role policies and taxpayer ownership. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationMatrixTests {
    private static final List<String> ROLES = List.of("RENTAS", "SUPERVISOR", "CASHIER", "AUDITOR", "TAXPAYER", "TECHNICAL");
    private static final Map<String, Set<String>> ROLE_POLICY = rolePolicies();
    private static final Map<String, String> AUTHENTICATION_EXCEPTIONS = Map.of(
        "GET /api/v1/health", "public",
        "POST /api/v1/dev-auth/login", "public",
        "POST /api/v1/dev-auth/bootstrap", "public",
        "POST /api/v1/dev-auth/logout", "authenticated",
        "GET /api/v1/dev-auth/me", "authenticated"
    );

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;
    @Autowired ApplicationContext applicationContext;
    @Autowired MockMvc mvc;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired DemoUserRepository users;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired DemoAuthService demoAuth;

    private PreAuthorizeAuthorizationManager authorization;

    @BeforeEach
    void configureAuthorizationManager() {
        authorization = new PreAuthorizeAuthorizationManager();
        authorization.setApplicationContext(applicationContext);
    }

    @Test
    void everyApiMappingHasKnownRolePolicyOrExplicitAuthenticationException() {
        Map<String, String> unexpected = new LinkedHashMap<>();
        Set<String> seenAuthenticationExceptions = new HashSet<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo mapping = entry.getKey();
            Set<String> paths = mapping.getPatternValues();
            if (paths.stream().noneMatch(path -> path.startsWith("/api/v1/"))) continue;
            HandlerMethod handler = entry.getValue();
            PreAuthorize annotation = preAuthorize(handler);
            for (String path : paths) {
                if (annotation != null) {
                    assertThat(canonicalPolicy(annotation.value()))
                        .as("Política no incluida en la matriz: %s %s -> %s", methods(mapping), path, annotation.value())
                        .isIn(ROLE_POLICY.keySet());
                    continue;
                }
                for (String method : methods(mapping)) {
                    String key = method + " " + path;
                    if (AUTHENTICATION_EXCEPTIONS.containsKey(key)) seenAuthenticationExceptions.add(key);
                    else unexpected.put(key, handler.toString());
                }
            }
        }

        assertThat(unexpected)
            .as("Mappings API sin @PreAuthorize fuera de las excepciones públicas/autenticadas explícitas")
            .isEmpty();
        assertThat(seenAuthenticationExceptions)
            .as("Excepciones públicas/autenticadas que dejaron de estar explícitas en el inventario")
            .containsExactlyInAnyOrderElementsOf(AUTHENTICATION_EXCEPTIONS.keySet());
    }

    @ParameterizedTest(name = "{0} permite {1}: {2}")
    @MethodSource("mappedEndpointRoleCases")
    void everyAnnotatedApiEndpointEnforcesTheDocumentedRoleMatrix(HandlerMethod handler, String role, boolean expected) {
        HandlerMethod resolved = handler.createWithResolvedBean();
        Method method = resolved.getMethod();
        MethodInvocation invocation = new MethodInvocation() {
            @Override public Method getMethod() { return method; }
            @Override public Object[] getArguments() { return new Object[method.getParameterCount()]; }
            @Override public Object getThis() { return resolved.getBean(); }
            @Override public AccessibleObject getStaticPart() { return method; }
            @Override public Object proceed() { throw new UnsupportedOperationException(); }
        };
        List<GrantedAuthority> authorities = effectiveRoles(role).stream()
            .map(name -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + name)).toList();
        var identity = new AuthenticatedIdentity("authorization-matrix-test", "TAXPAYER".equals(role) ? 10L : null, UUID.randomUUID());
        var authentication = new UsernamePasswordAuthenticationToken(identity, null, authorities);
        var decision = authorization.check(() -> authentication, invocation);

        assertThat(decision)
            .as("Sin decisión de autorización para %s#%s con %s", handler.getBeanType().getSimpleName(), method.getName(), role)
            .isNotNull();
        assertThat(decision.isGranted())
            .as("%s#%s con rol %s; policy=%s", handler.getBeanType().getSimpleName(), method.getName(), role, preAuthorize(handler).value())
            .isEqualTo(expected);
    }

    Stream<Arguments> mappedEndpointRoleCases() {
        return handlerMapping.getHandlerMethods().entrySet().stream()
            .filter(entry -> entry.getKey().getPatternValues().stream().anyMatch(path -> path.startsWith("/api/v1/")))
            .map(Map.Entry::getValue)
            .distinct()
            .flatMap(handler -> {
                PreAuthorize annotation = preAuthorize(handler);
                if (annotation == null) return Stream.empty();
                Set<String> allowedRoles = ROLE_POLICY.get(canonicalPolicy(annotation.value()));
                if (allowedRoles == null) throw new AssertionError("Política desconocida en " + handler + ": " + annotation.value());
                return ROLES.stream().map(role -> Arguments.of(handler, role,
                    effectiveRoles(role).stream().anyMatch(allowedRoles::contains)));
            });
    }

    @Test
    void taxpayerCanReadOwnSummaryButNotAnotherTaxpayersSummary() throws Exception {
        TaxpayerReference owner = taxpayer("MATRIX-OWNER");
        TaxpayerReference other = taxpayer("MATRIX-OTHER");
        String session = new DemoAuthSessions(users, taxpayers, passwordEncoder, demoAuth).token(DemoRole.TAXPAYER, owner.id);

        mvc.perform(get("/api/v1/taxpayers/{id}/summary", owner.id).header(DemoAuthService.SESSION_HEADER, session))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxpayers/{id}/summary", other.id).header(DemoAuthService.SESSION_HEADER, session))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
    }

    @Test
    void rolesWithoutWriteAuthorityAreRejectedBeforeEndpointBinding() throws Exception {
        String auditor = new DemoAuthSessions(users, taxpayers, passwordEncoder, demoAuth).token(DemoRole.AUDITOR);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/tax-concepts")
                .header(DemoAuthService.SESSION_HEADER, auditor)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{invalid-json"))
            .andExpect(status().isForbidden());
    }

    private TaxpayerReference taxpayer(String externalId) {
        TaxpayerReference taxpayer = new TaxpayerReference();
        taxpayer.taxpayerType = TaxpayerType.CITIZEN;
        taxpayer.externalId = externalId + "-" + UUID.randomUUID();
        taxpayer.displayName = "Fixture de autorización";
        taxpayer.externalStatus = TaxpayerStatus.ACTIVE;
        taxpayer.createdAt = taxpayer.updatedAt = OffsetDateTime.now();
        return taxpayers.saveAndFlush(taxpayer);
    }

    private static PreAuthorize preAuthorize(HandlerMethod handler) {
        PreAuthorize method = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        return method != null ? method : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
    }

    private static Set<String> effectiveRoles(String role) {
        return "SUPERVISOR".equals(role) ? Set.of("RENTAS", "SUPERVISOR") : Set.of(role);
    }

    private static Set<String> methods(RequestMappingInfo mapping) {
        Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
        if (methods.isEmpty()) return Set.of("*");
        return methods.stream().map(Enum::name).collect(java.util.stream.Collectors.toSet());
    }

    private static String canonicalPolicy(String expression) {
        return expression.replace('"', '\'').replaceAll("\\s+", "");
    }

    private static Map<String, Set<String>> rolePolicies() {
        Map<String, Set<String>> policies = new LinkedHashMap<>();
        policies.put("hasAnyRole('CASHIER','RENTAS','AUDITOR','TAXPAYER')", Set.of("CASHIER", "RENTAS", "AUDITOR", "TAXPAYER"));
        policies.put("hasAnyRole('RENTAS','AUDITOR','TAXPAYER')", Set.of("RENTAS", "AUDITOR", "TAXPAYER"));
        policies.put("hasAnyRole('RENTAS','AUDITOR')", Set.of("RENTAS", "AUDITOR"));
        policies.put("hasAnyRole('RENTAS','CASHIER','AUDITOR','TAXPAYER')", Set.of("RENTAS", "CASHIER", "AUDITOR", "TAXPAYER"));
        policies.put("hasAnyRole('RENTAS','CASHIER','AUDITOR')", Set.of("RENTAS", "CASHIER", "AUDITOR"));
        policies.put("hasAnyRole('RENTAS','CASHIER','TAXPAYER')", Set.of("RENTAS", "CASHIER", "TAXPAYER"));
        policies.put("hasAnyRole('RENTAS','CASHIER')", Set.of("RENTAS", "CASHIER"));
        policies.put("hasAnyRole('RENTAS','SUPERVISOR','AUDITOR','TAXPAYER')", Set.of("RENTAS", "SUPERVISOR", "AUDITOR", "TAXPAYER"));
        policies.put("hasAnyRole('RENTAS','SUPERVISOR','AUDITOR')", Set.of("RENTAS", "SUPERVISOR", "AUDITOR"));
        policies.put("hasAnyRole('RENTAS','SUPERVISOR','CASHIER','AUDITOR','TAXPAYER')", Set.of("RENTAS", "SUPERVISOR", "CASHIER", "AUDITOR", "TAXPAYER"));
        policies.put("hasAnyRole('RENTAS','SUPERVISOR','CASHIER','AUDITOR')", Set.of("RENTAS", "SUPERVISOR", "CASHIER", "AUDITOR"));
        policies.put("hasAnyRole('RENTAS','SUPERVISOR','CASHIER')", Set.of("RENTAS", "SUPERVISOR", "CASHIER"));
        policies.put("hasAnyRole('RENTAS','SUPERVISOR')", Set.of("RENTAS", "SUPERVISOR"));
        policies.put("hasAnyRole('RENTAS','TAXPAYER')", Set.of("RENTAS", "TAXPAYER"));
        policies.put("hasAnyRole('SUPERVISOR','AUDITOR','TECHNICAL')", Set.of("SUPERVISOR", "AUDITOR", "TECHNICAL"));
        policies.put("hasAnyRole('SUPERVISOR','AUDITOR')", Set.of("SUPERVISOR", "AUDITOR"));
        policies.put("hasAnyRole('SUPERVISOR','TECHNICAL')", Set.of("SUPERVISOR", "TECHNICAL"));
        policies.put("hasAnyRole('TAXPAYER','AUDITOR')", Set.of("TAXPAYER", "AUDITOR"));
        policies.put("hasRole('CASHIER')", Set.of("CASHIER"));
        policies.put("hasRole('RENTAS')", Set.of("RENTAS"));
        policies.put("hasRole('SUPERVISOR')", Set.of("SUPERVISOR"));
        policies.put("hasRole('TAXPAYER')", Set.of("TAXPAYER"));
        return Map.copyOf(policies);
    }
}
