package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El perfil dev tampoco habilita DEMO sin una decisión explícita de configuración. */
@ActiveProfiles({"test","dev"}) @SpringBootTest @AutoConfigureMockMvc
class DemoAuthDisabledTests {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test void perfilDevNoHabilitaAuthDemoPorDefecto() throws Exception {
        assertThat(context.getEnvironment().getProperty("rentas.security.dev-mode",Boolean.class)).isFalse();
        assertThat(context.getBeansOfType(DemoAuthService.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoAuthController.class)).isEmpty();
        mvc.perform(get("/api/v1/dev-auth/me").header("X-Demo-Session","sesion-forjada")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/dev-auth/login").contentType("application/json")
            .content("{\"username\":\"qa\",\"password\":\"fixture\"}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isBetween(400,499));
    }
}
