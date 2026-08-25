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
import java.io.IOException;
import java.util.Arrays;

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
class DevIdentityFilter extends OncePerRequestFilter {
    private final boolean enabled;
    DevIdentityFilter(@Value("${rentas.security.dev-mode:false}") boolean enabled) { this.enabled = enabled; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (enabled && SecurityContextHolder.getContext().getAuthentication() == null) {
            String user = value(request, "X-Dev-User", "dev-rentas");
            String roles = value(request, "X-Dev-Roles", "RENTAS,SUPERVISOR,CASHIER");
            String taxpayer = request.getHeader("X-Dev-Taxpayer-Id");
            Long taxpayerId = taxpayer == null || taxpayer.isBlank() ? null : Long.valueOf(taxpayer);
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
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
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
