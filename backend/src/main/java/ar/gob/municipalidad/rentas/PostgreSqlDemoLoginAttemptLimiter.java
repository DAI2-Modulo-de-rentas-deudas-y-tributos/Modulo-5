package ar.gob.municipalidad.rentas;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!prod & !test")
@ConditionalOnProperty(name="rentas.security.dev-mode", havingValue="true")
class PostgreSqlDemoLoginAttemptLimiter implements DemoLoginAttemptLimiter {
    private static final String RECORD_FAILURE = """
        INSERT INTO demo_login_attempt (attempt_key_hash, window_started_at, attempts)
        VALUES (?, CURRENT_TIMESTAMP, 1)
        ON CONFLICT (attempt_key_hash) DO UPDATE SET
          window_started_at = CASE
            WHEN demo_login_attempt.window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
              THEN CURRENT_TIMESTAMP ELSE demo_login_attempt.window_started_at END,
          attempts = CASE
            WHEN demo_login_attempt.window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
              THEN 1 ELSE demo_login_attempt.attempts + 1 END
        WHERE demo_login_attempt.window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
           OR demo_login_attempt.attempts < 5
        RETURNING attempts
        """;

    private final JdbcTemplate jdbc;

    PostgreSqlDemoLoginAttemptLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean recordFailure(String hashedKey) {
        var attempts = jdbc.query(RECORD_FAILURE, (rs, row) -> rs.getInt(1), hashedKey);
        jdbc.update("DELETE FROM demo_login_attempt WHERE window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'");
        return !attempts.isEmpty() && attempts.get(0) <= 5;
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reset(String hashedKey) {
        jdbc.update("DELETE FROM demo_login_attempt WHERE attempt_key_hash = ?", hashedKey);
    }
}
