package ar.gob.municipalidad.rentas;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(CitizenAuthController.PATH)
@Profile("citizen-auth-local & !prod & !staging & !dev & !aws-test")
@ConditionalOnProperty(name = "rentas.citizen-auth.enabled", havingValue = "true")
class CitizenAuthController {
    static final String PATH = "/api/v1/citizen-auth";
    static final String COOKIE = "__Host-rentas-citizen";
    static final String CSRF_HEADER = "X-Rentas-CSRF";

    private final CitizenSessions sessions;

    CitizenAuthController(CitizenSessions sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/login")
    ResponseEntity<CitizenSessions.ProfileResponse> login(@Valid @RequestBody Credentials body, HttpServletRequest request) {
        var login = sessions.login(body.username().trim(), body.password(), request.getRemoteAddr(), token(request));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(login.token(), login.ttlSeconds()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(login.profile());
    }

    @GetMapping("/me")
    ResponseEntity<CitizenSessions.ProfileResponse> me(HttpServletRequest request) {
        var identity = sessions.resolve(token(request))
                .orElseThrow(() -> new BusinessException("UNAUTHENTICATED", "Se requiere una sesión ciudadana vigente", 401));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(CitizenSessions.profile(identity.taxpayerId()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.revoke(token(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    static String token(HttpServletRequest request) {
        String token = null;
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (!COOKIE.equals(cookie.getName())) continue;
                if (token != null) return null;
                token = cookie.getValue();
            }
        }
        return token;
    }

    private static String cookie(String value, int seconds) {
        return ResponseCookie.from(COOKIE, value).secure(true).httpOnly(true)
                .path("/").sameSite("Strict").maxAge(seconds).build().toString();
    }

    record Credentials(@NotBlank @Size(max = 254) String username,
                       @NotBlank @Size(max = 256) String password) {
        @Override public String toString() { return "CitizenCredentials[redacted]"; }
    }
}
