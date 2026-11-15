package ar.gob.municipalidad.rentas;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("test")
@ConditionalOnProperty(name="rentas.security.dev-mode", havingValue="true")
class NoopDemoLoginAttemptLimiter implements DemoLoginAttemptLimiter {
    @Override public boolean allowAttempt(String hashedKey) { return true; }
    @Override public int retryAfterAttempt(String hashedKey) { return 1; }
    @Override public boolean allowOriginAttempt(String hashedOriginKey,long maximumFailures) { return true; }
    @Override public int retryAfterOriginAttempt(String hashedOriginKey) { return 1; }
    @Override public boolean recordOriginFailure(String hashedOriginKey,long maximumFailures) { return true; }
    @Override public void reset(String hashedKey) { }
}
