package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// SCRUM-818: si existe el usuario en memoria de Spring, su contraseña generada sale en el log de arranque.
@ActiveProfiles("test")
@SpringBootTest
class GeneratedPasswordStartupTests {
    @Autowired ApplicationContext context;

    @Test
    void noCreaElUsuarioEnMemoriaQueImprimeUnaContrasenaGenerada() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }
}
