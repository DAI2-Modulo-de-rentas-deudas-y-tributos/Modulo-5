package ar.gob.municipalidad.rentas;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("test")
@ConditionalOnProperty(name="rentas.security.dev-mode", havingValue="true")
class NoopDemoLoginAttemptLimiter implements DemoLoginAttemptLimiter {
    @Override public boolean recordFailure(String hashedKey) { return true; }
    @Override public void reset(String hashedKey) { }
}
