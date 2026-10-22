package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${rentas.cors.allowed-origins:}") String origins) {
        UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource();
        List<String> allowed=Arrays.stream(origins.split(",")).map(String::trim).filter(s->!s.isBlank()).distinct().toList();
        if(allowed.stream().anyMatch(origin->origin.contains("*")||origin.equals("null"))) throw new IllegalArgumentException("CORS_ALLOWED_ORIGINS debe contener orígenes explícitos, sin comodines ni null");
        if(allowed.isEmpty()) return source;
        CorsConfiguration config=new CorsConfiguration();
        config.setAllowedOrigins(allowed);
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type","Accept","Authorization","X-Demo-Session","Idempotency-Key","X-Correlation-Id"));
        config.setExposedHeaders(List.of("X-Correlation-Id","Content-Disposition","Allow"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/**",config);
        return source;
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, DevIdentityFilter filter,
            SecurityErrorResponseWriter errors) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .exceptionHandling(securityErrors -> securityErrors
                .authenticationEntryPoint((request,response,error) -> errors.write(request,response,401,
                    "UNAUTHENTICATED","Se requiere autenticación"))
                .accessDeniedHandler((request,response,error) -> errors.write(request,response,403,
                    "ACCESS_DENIED","No tiene permisos para realizar esta operación")))
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/actuator/health", "/api/v1/health", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/dev-auth/login", "/api/v1/dev-auth/bootstrap").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/**").authenticated()
                .anyRequest().authenticated())
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}

@Component
class SecurityErrorResponseWriter {
    private final ObjectMapper json;

    SecurityErrorResponseWriter(ObjectMapper json) { this.json=json; }

    void write(HttpServletRequest request,HttpServletResponse response,int status,String code,String message)
            throws IOException {
        String traceId=MDC.get("traceId");
        if(traceId==null||traceId.isBlank()) {
            String supplied=request.getHeader(CorrelationIdFilter.HEADER);
            traceId=supplied!=null&&supplied.matches("[A-Za-z0-9._:-]{1,100}")?supplied:UUID.randomUUID().toString();
        }
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader(CorrelationIdFilter.HEADER,traceId);
        json.writeValue(response.getOutputStream(),new ApiDtos.ErrorResponse(
            java.time.OffsetDateTime.now(),status,code,message,request.getRequestURI(),traceId,null));
    }
}

record AuthenticatedIdentity(String userId, Long taxpayerId) {
    @Override public String toString() { return userId; }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {
    static final String HEADER="X-Correlation-Id";
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String supplied=request.getHeader(HEADER);
        String correlationId=supplied!=null&&supplied.matches("[A-Za-z0-9._:-]{1,100}")?supplied:UUID.randomUUID().toString();
        MDC.put("traceId",correlationId);response.setHeader(HEADER,correlationId);
        try { chain.doFilter(request,response); } finally { MDC.remove("traceId"); }
    }
}

@Component
class DevIdentityFilter extends OncePerRequestFilter {
    private final boolean enabled;
    private final ObjectProvider<DemoAuthService> demoAuth;
    private final SecurityErrorResponseWriter errors;
    DevIdentityFilter(@Value("${rentas.security.dev-mode:false}") boolean enabled,ObjectProvider<DemoAuthService> demoAuth,
            SecurityErrorResponseWriter errors) {
        this.enabled=enabled;this.demoAuth=demoAuth;this.errors=errors;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if(enabled && !publicPath(request) && SecurityContextHolder.getContext().getAuthentication()==null) {
            String token=request.getHeader(DemoAuthService.SESSION_HEADER);
            if(token==null||token.isBlank()) {
                chain.doFilter(request,response);
                return;
            }
            DemoAuthService auth=demoAuth.getIfAvailable();
            if(auth==null) { chain.doFilter(request,response); return; }
            try {
                DemoAuthService.ResolvedSession session=auth.resolve(token);
                var authentication=new UsernamePasswordAuthenticationToken(session.identity(),null,session.authorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch(BusinessException ignored) {
                errors.write(request,response,HttpServletResponse.SC_UNAUTHORIZED,"UNAUTHENTICATED","Sesión DEMO inválida");
                return;
            }
        }
        chain.doFilter(request,response);
    }

    private boolean publicPath(HttpServletRequest request) {
        String path=request.getRequestURI();
        String method=request.getMethod();
        if("OPTIONS".equalsIgnoreCase(method)) return true;
        if(path.startsWith("/actuator/")||path.equals("/api/v1/health")||path.startsWith("/swagger-ui")||path.startsWith("/v3/api-docs")) return true;
        return "POST".equalsIgnoreCase(method)&&(path.equals("/api/v1/dev-auth/login")||path.equals("/api/v1/dev-auth/bootstrap"));
    }

}

@Component
class CurrentIdentity {
    AuthenticatedIdentity get() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null)throw new BusinessException("UNAUTHENTICATED","Se requiere autenticación",401);
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedIdentity identity) return identity;
        return new AuthenticatedIdentity(String.valueOf(principal), null);
    }
    String role() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .findFirst().map(Object::toString).orElse("ROLE_UNKNOWN");
    }
    boolean hasRole(String role) {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
    void requireOwnership(Long taxpayerId) {
        boolean taxpayer = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_TAXPAYER"));
        if (taxpayer && (taxpayerId == null || !taxpayerId.equals(get().taxpayerId()))) throw new BusinessException("FORBIDDEN_OWNERSHIP", "No puede acceder a datos de otro contribuyente", 403);
    }
}
