package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cuentas propias del personal (ADR 0013): alta desde STAFF_ACCOUNTS, sesión Bearer, roles y bloqueo. */
@ActiveProfiles("test") @SpringBootTest @AutoConfigureMockMvc @Transactional
class StaffAuthFlowTests {
    private static final String HASH=new BCryptPasswordEncoder(10).encode("clave-fixture");

    @DynamicPropertySource static void accounts(DynamicPropertyRegistry registry) {
        registry.add("rentas.staff.accounts",()->"["
            +"{\"username\":\"Supervisora.Staff\",\"displayName\":\"Ana Supervisora\",\"role\":\"SUPERVISOR\",\"passwordHash\":\""+HASH+"\"},"
            +"{\"username\":\"cajero.staff\",\"displayName\":\"Juan Cajero\",\"role\":\"CASHIER\",\"passwordHash\":\""+HASH+"\"},"
            +"{\"username\":\"bloqueo.staff\",\"displayName\":\"Prueba Bloqueo\",\"role\":\"AUDITOR\",\"passwordHash\":\""+HASH+"\"}]");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StaffAuthService staff;
    @Autowired StaffAccountRepository accounts;
    @Autowired AuditRepository audit;

    @Test void provisionedSupervisorLogsInOperatesAndLogsOut() throws Exception {
        JsonNode login=read(login("supervisora.staff","clave-fixture").andExpect(status().isOk())
            .andExpect(jsonPath("$.user.role").value("SUPERVISOR"))
            .andExpect(jsonPath("$.user.authorities[0]").value("RENTAS"))
            .andExpect(jsonPath("$.user.authorities[1]").value("SUPERVISOR"))
            .andExpect(jsonPath("$.user.authSource").value("STAFF")));
        String bearer="Bearer "+login.path("token").asText();
        mvc.perform(get("/api/v1/auth/staff/me").header("Authorization",bearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Ana Supervisora"));
        mvc.perform(get("/api/v1/taxpayers").header("Authorization",bearer)).andExpect(status().isOk());
        assertThat(audit.findAll()).anyMatch(e->"STAFF_LOGIN_SUCCEEDED".equals(e.action)&&"supervisora.staff".equals(e.userId));

        mvc.perform(post("/api/v1/auth/staff/logout").header("Authorization",bearer)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/staff/me").header("Authorization",bearer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/taxpayers").header("Authorization",bearer)).andExpect(status().isUnauthorized());
    }

    @Test void cashierOnlyGetsItsOwnRole() throws Exception {
        String bearer="Bearer "+read(login("cajero.staff","clave-fixture").andExpect(status().isOk())
            .andExpect(jsonPath("$.user.authorities.length()").value(1))).path("token").asText();
        mvc.perform(post("/api/v1/taxpayers/{id}/canonical-citizen-link",1L).header("Authorization",bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test void wrongPasswordUnknownUserAndInactiveAccountAreRejectedAlike() throws Exception {
        login("supervisora.staff","otra-clave").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("no.existe","clave-fixture").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        StaffAccount cashier=accounts.findByUsername("cajero.staff").orElseThrow();
        cashier.active=false;accounts.saveAndFlush(cashier);
        login("cajero.staff","clave-fixture").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test void repeatedFailuresLockTheAccountTemporarily() throws Exception {
        for(int attempt=0;attempt<5;attempt++)login("bloqueo.staff","otra-clave").andExpect(status().isUnauthorized());
        login("bloqueo.staff","clave-fixture").andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("STAFF_LOGIN_RATE_LIMITED"));
    }

    @Test void provisioningDisablesAccountsThatLeftTheList() {
        StaffAccount former=new StaffAccount();
        former.username="ex.empleado";former.passwordHash=HASH;former.displayName="Ex Empleado";former.role="RENTAS";
        former.integrationActorId=UUID.randomUUID();former.active=true;former.createdAt=former.updatedAt=OffsetDateTime.now();
        accounts.saveAndFlush(former);
        staff.provision();
        assertThat(accounts.findByUsername("ex.empleado").orElseThrow().active).isFalse();
        assertThat(accounts.findByUsername("supervisora.staff").orElseThrow().active).isTrue();
    }

    @Test void provisioningRejectsInvalidConfiguration() {
        assertThatThrownBy(()->staff.parse("no-es-json")).hasMessageContaining("STAFF_ACCOUNTS_INVALID");
        assertThatThrownBy(()->staff.parse("[{\"username\":\"ana\",\"displayName\":\"Ana\",\"role\":\"SUPERVISOR\",\"passwordHash\":\"clave-en-claro\"}]"))
            .hasMessageContaining("ana");
        assertThatThrownBy(()->staff.parse("[{\"username\":\"ana\",\"displayName\":\"Ana\",\"role\":\"TAXPAYER\",\"passwordHash\":\""+HASH+"\"}]"))
            .hasMessageContaining("ana");
        assertThatThrownBy(()->staff.parse("[{\"username\":\"ana\",\"displayName\":\"Ana\",\"role\":\"RENTAS\",\"passwordHash\":\""+HASH+"\"},"
            +"{\"username\":\"ANA\",\"displayName\":\"Ana 2\",\"role\":\"RENTAS\",\"passwordHash\":\""+HASH+"\"}]")).hasMessageContaining("ana");
    }

    @Test void credentialsAreRedactedFromToString() {
        assertThat(new StaffAuthService.LoginRequest("ana","secreta").toString()).doesNotContain("secreta");
        assertThat(new StaffAuthService.LoginResponse("token-opaco",null).toString()).doesNotContain("token-opaco");
        assertThat(new StaffAuthService.ProvisionedAccount("ana","Ana","RENTAS",HASH).toString()).doesNotContain(HASH);
    }

    private ResultActions login(String username,String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username",username,"password",password))));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
