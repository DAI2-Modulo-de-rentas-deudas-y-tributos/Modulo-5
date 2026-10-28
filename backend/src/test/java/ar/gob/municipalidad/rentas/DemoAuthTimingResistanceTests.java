package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DemoAuthTimingResistanceTests {
    @Mock DemoUserRepository users;
    @Mock DemoBootstrapLockRepository bootstrapLock;
    @Mock DemoAuthSessionRepository sessions;
    @Mock TaxpayerRepository taxpayers;
    @Mock PasswordEncoder passwords;
    @Mock AuditService audit;
    @Mock DemoLoginAttemptLimiter loginAttempts;

    private DemoAuthService service;

    @BeforeEach void setup() {
        when(passwords.encode(anyString())).thenReturn("dummy-bcrypt-hash");
        when(loginAttempts.allowAttempt(anyString())).thenReturn(true);
        service=new DemoAuthService(users,bootstrapLock,sessions,taxpayers,passwords,audit,loginAttempts,"",Duration.ofHours(8));
    }

    @Test void missingAndInactiveUsersPerformPasswordCheckAndReturnSameGenericError() {
        var request=new DemoAuthController.LoginRequest("missing-user","same-password");
        when(users.findByUsernameIgnoreCase("missing-user")).thenReturn(Optional.empty());
        BusinessException missing=captureInvalidLogin(request);

        DemoUser inactive=new DemoUser();
        inactive.username="inactive-user";
        inactive.passwordHash="stored-user-hash";
        inactive.active=false;
        when(users.findByUsernameIgnoreCase("inactive-user")).thenReturn(Optional.of(inactive));
        BusinessException disabled=captureInvalidLogin(new DemoAuthController.LoginRequest("inactive-user","same-password"));

        assertThat(missing.code).isEqualTo("INVALID_CREDENTIALS");
        assertThat(missing.status).isEqualTo(401);
        assertThat(missing.getMessage()).isEqualTo(disabled.getMessage());
        assertThat(disabled.code).isEqualTo(missing.code);
        assertThat(disabled.status).isEqualTo(missing.status);
        verify(passwords,times(2)).matches("same-password","dummy-bcrypt-hash");
        verify(passwords,never()).matches("same-password","stored-user-hash");
        verifyNoInteractions(sessions);
    }

    @Test void activeUserStillChecksItsStoredHash() {
        DemoUser active=new DemoUser();
        active.username="active-user";
        active.passwordHash="stored-user-hash";
        active.active=true;
        when(users.findByUsernameIgnoreCase("active-user")).thenReturn(Optional.of(active));
        when(passwords.matches("wrong-password","stored-user-hash")).thenReturn(false);

        BusinessException failure=captureInvalidLogin(new DemoAuthController.LoginRequest("active-user","wrong-password"));

        assertThat(failure.code).isEqualTo("INVALID_CREDENTIALS");
        assertThat(failure.status).isEqualTo(401);
        verify(passwords).matches("wrong-password","stored-user-hash");
    }

    private BusinessException captureInvalidLogin(DemoAuthController.LoginRequest request) {
        return catchThrowableOfType(() -> service.login(request,"127.0.0.1"),BusinessException.class);
    }
}
