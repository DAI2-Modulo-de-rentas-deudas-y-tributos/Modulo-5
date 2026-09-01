package ar.gob.municipalidad.rentas;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.slf4j.MDC;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, DevIdentityFilter filter) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/api/v1/health", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/**").authenticated()
                .anyRequest().authenticated())
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
            .build();
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
    DevIdentityFilter(@Value("${rentas.security.dev-mode:false}") boolean enabled) { this.enabled = enabled; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (enabled && SecurityContextHolder.getContext().getAuthentication() == null) {
            String user = value(request, "X-Dev-User", "dev-rentas");
            String roles = value(request, "X-Dev-Roles", "RENTAS,SUPERVISOR,CASHIER");
            String taxpayer = request.getHeader("X-Dev-Taxpayer-Id");
            Long taxpayerId;
            try { taxpayerId = taxpayer == null || taxpayer.isBlank() ? null : Long.valueOf(taxpayer); }
            catch (NumberFormatException ex) { response.sendError(HttpServletResponse.SC_BAD_REQUEST,"X-Dev-Taxpayer-Id inválido"); return; }
            var authorities = Arrays.stream(roles.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).map(s -> new SimpleGrantedAuthority("ROLE_" + s)).toList();
            var auth = new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(user, taxpayerId), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
    private String value(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name); return value == null || value.isBlank() ? fallback : value;
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
    void requireOwnership(Long taxpayerId) {
        boolean taxpayer = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_TAXPAYER"));
        if (taxpayer && (taxpayerId == null || !taxpayerId.equals(get().taxpayerId()))) throw new BusinessException("FORBIDDEN_OWNERSHIP", "No puede acceder a datos de otro contribuyente", 403);
    }
}
