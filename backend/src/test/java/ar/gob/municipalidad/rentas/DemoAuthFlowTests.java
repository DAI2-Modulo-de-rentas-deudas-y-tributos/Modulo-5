package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Regresiones HTTP: bootstrap y creación reales, sin autenticación inyectada ni roles del navegador. */
@ActiveProfiles("test") @SpringBootTest @AutoConfigureMockMvc @Transactional
@org.springframework.test.context.jdbc.Sql(statements="INSERT INTO demo_bootstrap_lock(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM demo_bootstrap_lock WHERE id=1)")
class DemoAuthFlowTests {
    private static final String PASSWORD="clave-solo-fixture";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DemoUserRepository users;
    @Autowired DemoAuthSessionRepository sessions;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    @BeforeEach void baseVacia() {
        sessions.deleteAll();users.deleteAll();users.flush();
    }

    @Test void bootstrapCorrecto() throws Exception {
        bootstrap().andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("SUPERVISOR"));
        DemoUser stored=users.findByUsernameIgnoreCase("qa.supervisor").orElseThrow();
        assertThat(encoder.matches(PASSWORD,stored.passwordHash)).isTrue();
        assertThat(stored.passwordHash).isNotEqualTo(PASSWORD);
    }
    @Test void segundoBootstrapRechazado() throws Exception {
        bootstrap().andExpect(status().isCreated());
        bootstrap().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEMO_BOOTSTRAP_ALREADY_COMPLETED"));
        assertThat(users.count()).isEqualTo(1);
    }
    @Test void bootstrapSinSecretoRechazado() throws Exception {
        mvc.perform(post("/api/v1/dev-auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username","qa.supervisor","password",PASSWORD,"displayName","QA"))))
            .andExpect(status().isUnauthorized());
        assertThat(users.count()).isZero();
    }
    @Test void loginCorrecto() throws Exception {
        bootstrap().andExpect(status().isCreated());
        login("qa.supervisor",PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value("qa.supervisor"));
    }
    @Test void passwordIncorrecto() throws Exception {
        bootstrap().andExpect(status().isCreated());
        login("qa.supervisor","incorrecta").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertThat(sessions.count()).isZero();
    }
    @Test void usuarioInexistente() throws Exception {
        login("no-existe",PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
    @Test void usuarioInactivoNoIngresa() throws Exception {
        bootstrap().andExpect(status().isCreated());
        desactivar("qa.supervisor");
        login("qa.supervisor",PASSWORD).andExpect(status().isUnauthorized());
    }
    @Test void usernameDuplicado() throws Exception {
        String supervisor=supervisor();
        crear(supervisor,"qa.usuario",DemoRole.RENTAS,null).andExpect(status().isCreated());
        crear(supervisor,"QA.USUARIO",DemoRole.RENTAS,null).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DEMO_USERNAME_ALREADY_EXISTS"));
        assertThat(users.count()).isEqualTo(2);
    }
    @Test void tokenAleatorioPorLogin() throws Exception {
        String first=supervisor();String second=token("qa.supervisor");
        assertThat(first).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(second);
    }
    @Test void baseAlmacenaSoloHashNoTokenCrudo() throws Exception {
        String token=supervisor();
        var stored=sessions.findByTokenHash(DemoAuthService.hashToken(token)).orElseThrow();
        assertThat(stored.tokenHash).matches("[0-9a-f]{64}").isNotEqualTo(token);
        assertThat(sessions.findByTokenHash(token)).isEmpty();
        assertThat(stored.expiresAt).isAfter(stored.createdAt);
    }
    @Test void meConSesionValida() throws Exception {
        mvc.perform(get("/api/v1/dev-auth/me").header("X-Demo-Session",supervisor()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authorities").value(org.hamcrest.Matchers.containsInAnyOrder("RENTAS","SUPERVISOR")));
    }
    @Test void meConSesionInvalida() throws Exception {
        mvc.perform(get("/api/v1/dev-auth/me").header("X-Demo-Session","sesion-inventada")).andExpect(status().isUnauthorized());
    }
    @Test void sesionExpirada() throws Exception {
        String token=supervisor();var stored=sessions.findByTokenHash(DemoAuthService.hashToken(token)).orElseThrow();
        stored.createdAt=OffsetDateTime.now().minusHours(2);stored.expiresAt=OffsetDateTime.now().minusSeconds(1);sessions.saveAndFlush(stored);
        mvc.perform(get("/api/v1/dev-auth/me").header("X-Demo-Session",token)).andExpect(status().isUnauthorized());
    }
    @Test void logoutRevocaPersistencia() throws Exception {
        String token=supervisor();
        mvc.perform(post("/api/v1/dev-auth/logout").header("X-Demo-Session",token)).andExpect(status().isNoContent());
        assertThat(sessions.findByTokenHash(DemoAuthService.hashToken(token)).orElseThrow().revokedAt).isNotNull();
    }
    @Test void tokenRevocadoNoSeReutiliza() throws Exception {
        String token=supervisor();
        mvc.perform(post("/api/v1/dev-auth/logout").header("X-Demo-Session",token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/tax-concepts").header("X-Demo-Session",token)).andExpect(status().isUnauthorized());
    }
    @Test void sinSesionNoHayAcceso() throws Exception {
        mvc.perform(get("/api/v1/tax-concepts")).andExpect(status().isUnauthorized());
    }
    @Test void cabecerasDevNoAutentican() throws Exception {
        mvc.perform(get("/api/v1/tax-concepts").header("X-Dev-Roles","SUPERVISOR,RENTAS")
            .header("X-Dev-User","forjado").header("X-Dev-Taxpayer-Id","1")).andExpect(status().isUnauthorized());
    }
    @Test void auditorNoEscribe() throws Exception {
        String supervisor=supervisor();crear(supervisor,"qa.auditor",DemoRole.AUDITOR,null).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/tax-concepts").header("X-Demo-Session",token("qa.auditor"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"QA\",\"name\":\"QA\",\"type\":\"FEE\",\"originModule\":\"M5\"}"))
            .andExpect(status().isForbidden());
    }
    @Test void contribuyenteConsultaPropio() throws Exception {
        var taxpayer=taxpayer("PROPIO");String token=contribuyente(taxpayer.id);
        mvc.perform(get("/api/v1/taxpayers/{id}/summary",taxpayer.id).header("X-Demo-Session",token)).andExpect(status().isOk());
    }
    @Test void contribuyenteNoConsultaAjeno() throws Exception {
        var own=taxpayer("PROPIO");var other=taxpayer("AJENO");String token=contribuyente(own.id);
        mvc.perform(get("/api/v1/taxpayers/{id}/summary",other.id).header("X-Demo-Session",token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
    }
    @Test void manipularRolClienteNoEscalaPrivilegios() throws Exception {
        String token=contribuyente(taxpayer("PROPIO").id);
        mvc.perform(get("/api/v1/dev-auth/users").header("X-Demo-Session",token)
            .header("X-Dev-Roles","SUPERVISOR").header("X-Dev-User","qa.supervisor")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dev-auth/me").header("X-Demo-Session",token).header("X-Dev-Roles","SUPERVISOR"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("TAXPAYER"));
    }
    @Test void desactivarUsuarioInvalidaSesionExistente() throws Exception {
        String token=supervisor();desactivar("qa.supervisor");
        mvc.perform(get("/api/v1/dev-auth/me").header("X-Demo-Session",token)).andExpect(status().isUnauthorized());
    }

    private ResultActions bootstrap() throws Exception {
        return mvc.perform(post("/api/v1/dev-auth/bootstrap").header("X-Demo-Bootstrap-Secret","test-bootstrap-secret")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username","qa.supervisor","password",PASSWORD,"displayName","QA Supervisor"))));
    }
    private ResultActions login(String username,String password) throws Exception {
        return mvc.perform(post("/api/v1/dev-auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username",username,"password",password))));
    }
    private String token(String username) throws Exception {
        return json.readTree(login(username,PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("token").asText();
    }
    private String supervisor() throws Exception {bootstrap().andExpect(status().isCreated());return token("qa.supervisor");}
    private ResultActions crear(String supervisor,String username,DemoRole role,Long taxpayerId) throws Exception {
        return mvc.perform(post("/api/v1/dev-auth/users").header("X-Demo-Session",supervisor).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new DemoAuthController.CreateUserRequest(username,PASSWORD,"QA",role,taxpayerId))));
    }
    private String contribuyente(Long taxpayerId) throws Exception {
        crear(supervisor(),"qa.contribuyente",DemoRole.TAXPAYER,taxpayerId).andExpect(status().isCreated());return token("qa.contribuyente");
    }
    private void desactivar(String username) {var user=users.findByUsernameIgnoreCase(username).orElseThrow();user.active=false;users.saveAndFlush(user);}
    private TaxpayerReference taxpayer(String externalId) {
        var t=new TaxpayerReference();t.taxpayerType=TaxpayerType.CITIZEN;t.externalId=externalId;t.displayName=externalId;
        t.externalStatus=TaxpayerStatus.ACTIVE;t.createdAt=t.updatedAt=OffsetDateTime.now();return taxpayers.saveAndFlush(t);
    }
}
