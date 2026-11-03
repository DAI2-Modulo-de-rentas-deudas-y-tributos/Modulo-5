package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class StartupSecurityAutoConfigurationTests {
    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void doesNotRegisterSpringGeneratedInMemoryUser() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }
}
