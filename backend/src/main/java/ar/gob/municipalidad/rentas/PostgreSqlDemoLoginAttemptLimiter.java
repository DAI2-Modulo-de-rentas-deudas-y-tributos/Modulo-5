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
    private static final String ADMIT_ATTEMPT = """
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
    private static final String RECORD_ORIGIN_FAILURE = """
        INSERT INTO demo_login_origin_attempt (origin_key_hash, window_started_at, failures)
        VALUES (?, CURRENT_TIMESTAMP, 1)
        ON CONFLICT (origin_key_hash) DO UPDATE SET
          window_started_at = CASE
            WHEN demo_login_origin_attempt.window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
              THEN CURRENT_TIMESTAMP ELSE demo_login_origin_attempt.window_started_at END,
          failures = CASE
            WHEN demo_login_origin_attempt.window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
              THEN 1
            WHEN demo_login_origin_attempt.failures >= ? THEN ?
            ELSE demo_login_origin_attempt.failures + 1 END
        RETURNING failures
        """;

    private final JdbcTemplate jdbc;

    PostgreSqlDemoLoginAttemptLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean allowAttempt(String hashedKey) {
        var attempts = jdbc.query(ADMIT_ATTEMPT, (rs, row) -> rs.getInt(1), hashedKey);
        jdbc.update("DELETE FROM demo_login_attempt WHERE window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'");
        return !attempts.isEmpty() && attempts.get(0) <= 5;
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean allowOriginAttempt(String hashedOriginKey,long maximumFailures) {
        jdbc.update("DELETE FROM demo_login_origin_attempt WHERE window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'");
        var failures=jdbc.query("SELECT failures FROM demo_login_origin_attempt WHERE origin_key_hash=?",
                (rs,row)->rs.getLong(1),hashedOriginKey);
        return failures.isEmpty()||failures.get(0)<maximumFailures;
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean recordOriginFailure(String hashedOriginKey,long maximumFailures) {
        long saturatedLimit=Math.addExact(maximumFailures,1);
        var failures=jdbc.query(RECORD_ORIGIN_FAILURE,(rs,row)->rs.getLong(1),hashedOriginKey,maximumFailures,saturatedLimit);
        jdbc.update("DELETE FROM demo_login_origin_attempt WHERE window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'");
        return !failures.isEmpty()&&failures.get(0)<=maximumFailures;
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reset(String hashedKey) {
        jdbc.update("DELETE FROM demo_login_attempt WHERE attempt_key_hash = ?", hashedKey);
    }
}
