package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("citizen-auth-local & !prod & !staging & !dev & !aws-test")
@ConditionalOnProperty(name = "rentas.citizen-auth.enabled", havingValue = "true")
class CitizenSessions {
    private final JdbcTemplate jdbc;
    private final TaxpayerRepository taxpayers;
    private final M1CitizenLoginClient client;
    private final SecureRandom random = new SecureRandom();
    private final int ttlSeconds;

    CitizenSessions(JdbcTemplate jdbc, TaxpayerRepository taxpayers, ObjectMapper json,
            @Value("${rentas.citizen-auth.base-url}") String baseUrl,
            @Value("${rentas.citizen-auth.timeout-ms:3000}") int timeoutMillis,
            @Value("${rentas.citizen-auth.session-seconds:600}") int ttlSeconds) {
        if (ttlSeconds < 60 || ttlSeconds > 900) throw new IllegalArgumentException("Duración de sesión ciudadana inválida");
        this.jdbc = jdbc;
        this.taxpayers = taxpayers;
        this.client = new M1CitizenLoginClient(baseUrl, json, timeoutMillis);
        this.ttlSeconds = ttlSeconds;
    }

    Login login(String username, String password, String origin, String previousToken) {
        reserveAttempt(origin);
        UUID citizenId = client.authenticate(username, password);
        TaxpayerReference taxpayer = taxpayers.findByCanonicalCitizenId(citizenId)
                .filter(t -> t.taxpayerType == TaxpayerType.CITIZEN && t.externalStatus == TaxpayerStatus.ACTIVE)
                .orElseThrow(M1CitizenLoginClient::denied);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        revoke(previousToken);
        jdbc.update("""
                INSERT INTO citizen_session(token_hash, taxpayer_id, citizen_id, expires_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 second'))
                """, hash(token), taxpayer.id, citizenId, ttlSeconds);
        return new Login(token, profile(taxpayer.id), ttlSeconds);
    }

    Optional<AuthenticatedIdentity> resolve(String token) {
        if (!validToken(token)) return Optional.empty();
        return jdbc.query("""
                SELECT s.taxpayer_id, s.citizen_id FROM citizen_session s
                JOIN taxpayer_reference t ON t.id = s.taxpayer_id AND t.canonical_citizen_id = s.citizen_id
                WHERE s.token_hash = ? AND s.expires_at > CURRENT_TIMESTAMP
                AND t.taxpayer_type = 'CITIZEN' AND t.external_status = 'ACTIVE'
                """, (rs, row) -> new AuthenticatedIdentity("citizen:" + rs.getLong(1),
                        rs.getLong(1), rs.getObject(2, UUID.class)), hash(token)).stream().findFirst();
    }

    void revoke(String token) {
        if (validToken(token)) jdbc.update("DELETE FROM citizen_session WHERE token_hash = ?", hash(token));
    }

    private void reserveAttempt(String origin) {
        long now = System.currentTimeMillis() / 1000;
        var admitted = jdbc.query("""
                INSERT INTO citizen_login_window AS w(origin_hash, window_end, attempts) VALUES (?, ?, 1)
                ON CONFLICT (origin_hash) DO UPDATE SET
                    attempts = CASE WHEN w.window_end <= ? THEN 1 ELSE w.attempts + 1 END,
                    window_end = CASE WHEN w.window_end <= ? THEN EXCLUDED.window_end ELSE w.window_end END
                WHERE w.window_end <= ? OR w.attempts < 4 RETURNING attempts
                """, (rs, row) -> rs.getInt(1), hash(origin), now + 60, now, now, now);
        if (admitted.isEmpty()) {
            throw new BusinessException("CITIZEN_LOGIN_RATE_LIMITED", "Volvé a intentar más tarde", 429, 60);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void removeExpired() {
        jdbc.update("DELETE FROM citizen_session WHERE expires_at <= CURRENT_TIMESTAMP");
        jdbc.update("DELETE FROM citizen_login_window WHERE window_end <= ?", System.currentTimeMillis() / 1000);
    }

    private static boolean validToken(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    private static String hash(String value) {
        return DemoAuthService.hashToken(value);
    }

    static ProfileResponse profile(long taxpayerId) {
        return new ProfileResponse("Contribuyente", "TAXPAYER", taxpayerId);
    }

    record Login(String token, ProfileResponse profile, int ttlSeconds) {
        @Override public String toString() { return "CitizenLogin[redacted]"; }
    }
    record ProfileResponse(String displayName, String role, long taxpayerId) {}
}
