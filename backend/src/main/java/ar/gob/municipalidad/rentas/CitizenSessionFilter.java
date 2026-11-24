package ar.gob.municipalidad.rentas;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

final class CitizenSessionFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private final ObjectProvider<CitizenSessions> sessions;
    private final SecurityErrorResponseWriter errors;

    CitizenSessionFilter(ObjectProvider<CitizenSessions> sessions, SecurityErrorResponseWriter errors) {
        this.sessions = sessions;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CitizenSessions service = sessions.getIfAvailable();
        if (service == null) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        boolean citizenEndpoint = path.startsWith(CitizenAuthController.PATH + "/");
        if (!citizenEndpoint && request.getHeader(DemoAuthService.SESSION_HEADER) != null) {
            chain.doFilter(request, response);
            return;
        }
        String token = CitizenAuthController.token(request);
        // Este encabezado obliga al navegador a hacer preflight fuera del origen. CORS no lo autoriza.
        if ((citizenEndpoint || token != null) && !SAFE.contains(request.getMethod())
                && (!"1".equals(request.getHeader(CitizenAuthController.CSRF_HEADER))
                    || Set.of("cross-site", "same-site").contains(
                        java.util.Objects.toString(request.getHeader("Sec-Fetch-Site"), "")))) {
            errors.write(request, response, 403, "CITIZEN_CSRF_REQUIRED", "La solicitud debe originarse en el Portal del Contribuyente");
            return;
        }
        if (!citizenEndpoint && path.startsWith("/api/v1/") && token != null) {
            service.resolve(token).ifPresent(identity -> SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(identity, null,
                        List.of(new SimpleGrantedAuthority("ROLE_TAXPAYER")))));
        }
        chain.doFilter(request, response);
    }
}
