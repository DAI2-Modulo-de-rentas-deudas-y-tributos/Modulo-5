package ar.gob.municipalidad.rentas;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!prod & !test")
@ConditionalOnProperty(name="rentas.security.dev-mode", havingValue="true")
class PostgreSqlDemoLoginAttemptLimiter implements DemoLoginAttemptLimiter {
    private static final int CLEANUP_BATCH_SIZE = 500;
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
    private static final String RETRY_AFTER_ATTEMPT = """
        SELECT GREATEST(1, CEIL(EXTRACT(EPOCH FROM (window_started_at + INTERVAL '15 minutes' - CURRENT_TIMESTAMP)))::integer)
        FROM demo_login_attempt
        WHERE attempt_key_hash = ?
          AND attempts >= 5
          AND window_started_at > CURRENT_TIMESTAMP - INTERVAL '15 minutes'
        """;
    private static final String RETRY_AFTER_ORIGIN = """
        SELECT GREATEST(1, CEIL(EXTRACT(EPOCH FROM (window_started_at + INTERVAL '15 minutes' - CURRENT_TIMESTAMP)))::integer)
        FROM demo_login_origin_attempt
        WHERE origin_key_hash = ?
          AND window_started_at > CURRENT_TIMESTAMP - INTERVAL '15 minutes'
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
    private static final String CLEAN_EXPIRED_ATTEMPTS = """
        WITH expired AS (
          SELECT attempt_key_hash
          FROM demo_login_attempt
          WHERE window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
          ORDER BY window_started_at
          LIMIT 500
          FOR UPDATE SKIP LOCKED
        )
        DELETE FROM demo_login_attempt attempt
        USING expired
        WHERE attempt.attempt_key_hash = expired.attempt_key_hash
        """;
    private static final String CLEAN_EXPIRED_ORIGIN_ATTEMPTS = """
        WITH expired AS (
          SELECT origin_key_hash
          FROM demo_login_origin_attempt
          WHERE window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
          ORDER BY window_started_at
          LIMIT 500
          FOR UPDATE SKIP LOCKED
        )
        DELETE FROM demo_login_origin_attempt attempt
        USING expired
        WHERE attempt.origin_key_hash = expired.origin_key_hash
        """;

    private final JdbcTemplate jdbc;

    PostgreSqlDemoLoginAttemptLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean allowAttempt(String hashedKey) {
        var attempts = jdbc.query(ADMIT_ATTEMPT, (rs, row) -> rs.getInt(1), hashedKey);
        return !attempts.isEmpty() && attempts.get(0) <= 5;
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int retryAfterAttempt(String hashedKey) {
        return retryAfter(RETRY_AFTER_ATTEMPT, hashedKey);
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean allowOriginAttempt(String hashedOriginKey,long maximumFailures) {
        var failures=jdbc.query("""
                SELECT failures FROM demo_login_origin_attempt
                WHERE origin_key_hash=?
                  AND window_started_at > CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                """,
                (rs,row)->rs.getLong(1),hashedOriginKey);
        return failures.isEmpty()||failures.get(0)<maximumFailures;
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int retryAfterOriginAttempt(String hashedOriginKey) {
        return retryAfter(RETRY_AFTER_ORIGIN, hashedOriginKey);
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean recordOriginFailure(String hashedOriginKey,long maximumFailures) {
        long saturatedLimit=Math.addExact(maximumFailures,1);
        var failures=jdbc.query(RECORD_ORIGIN_FAILURE,(rs,row)->rs.getLong(1),hashedOriginKey,maximumFailures,saturatedLimit);
        return !failures.isEmpty()&&failures.get(0)<=maximumFailures;
    }

    @Scheduled(fixedDelay=60_000L)
    public void cleanupExpiredAttempts() {
        // shortcut: bounded batches drain backlog; sustained expiry volume above DB delete throughput needs a dedicated cleanup worker.
        while (true) {
            int loginAttempts=jdbc.update(CLEAN_EXPIRED_ATTEMPTS);
            int originAttempts=jdbc.update(CLEAN_EXPIRED_ORIGIN_ATTEMPTS);
            if(loginAttempts<CLEANUP_BATCH_SIZE&&originAttempts<CLEANUP_BATCH_SIZE)return;
        }
    }

    private int retryAfter(String sql,String hashedKey) {
        var seconds=jdbc.query(sql,(rs,row)->rs.getInt(1),hashedKey);
        return seconds.isEmpty()?1:Math.max(1,seconds.get(0));
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reset(String hashedKey) {
        jdbc.update("DELETE FROM demo_login_attempt WHERE attempt_key_hash = ?", hashedKey);
    }
}
