package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login ciudadano real contra un M1 simulado: sesión propia, rol TAXPAYER y titularidad. */
@ActiveProfiles("test") @SpringBootTest(properties="rentas.m1.auth.enabled=true") @AutoConfigureMockMvc @Transactional
class M1CitizenAuthFlowTests {
    private static final AtomicReference<UUID> M1_CITIZEN=new AtomicReference<>();
    private static final AtomicReference<Integer> M1_STATUS=new AtomicReference<>(200);
    /** Campos de perfil que agrega el login de M1 (dni, cuit, nombre, apellido); vacío por defecto. */
    private static final AtomicReference<String> M1_PROFILE=new AtomicReference<>("");
    private static final String PROFILE=",\"dni\":31415926,\"cuit\":20314159263,\"nombre\":\"Ana\",\"apellido\":\"Paz\"";
    private static final M1AuthClientTests.StubM1 M1=stub();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired CitizenSessionRepository sessions;
    @Autowired AuditRepository audit;

    @DynamicPropertySource static void m1(DynamicPropertyRegistry registry) {
        registry.add("rentas.m1.auth.base-url",M1::url);
    }

    @AfterAll static void stopM1() { M1.close(); }

    @BeforeEach void defaults() { M1_STATUS.set(200); M1_CITIZEN.set(UUID.randomUUID()); M1_PROFILE.set(""); }

    @Test void citizenLogsInWithM1AndOperatesOnlyOnOwnTaxpayer() throws Exception {
        TaxpayerReference own=taxpayer(M1_CITIZEN.get(),TaxpayerStatus.ACTIVE);
        TaxpayerReference other=taxpayer(UUID.randomUUID(),TaxpayerStatus.ACTIVE);
        JsonNode login=read(login("ana@example.invalid").andExpect(status().isOk())
            .andExpect(jsonPath("$.user.role").value("TAXPAYER"))
            .andExpect(jsonPath("$.user.taxpayerId").value(own.id))
            .andExpect(jsonPath("$.user.authSource").value("M1")));
        String bearer="Bearer "+login.path("token").asText();
        assertThat(sessions.findAll()).anyMatch(s->s.canonicalCitizenId.equals(M1_CITIZEN.get())&&!s.tokenHash.contains(login.path("token").asText()));

        mvc.perform(get("/api/v1/auth/m1/me").header("Authorization",bearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value(own.displayName));
        mvc.perform(get("/api/v1/taxpayers/{id}/debts",own.id).header("Authorization",bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxpayers/{id}/debts",other.id).header("Authorization",bearer))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
        mvc.perform(get("/api/v1/taxpayers").header("Authorization",bearer)).andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/auth/m1/logout").header("Authorization",bearer)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/m1/me").header("Authorization",bearer)).andExpect(status().isUnauthorized());
    }

    @Test void firstLoginRegistersTheCitizenWithTheDataFromM1() throws Exception {
        M1_PROFILE.set(PROFILE);
        long references=taxpayers.count();
        long id=read(login("ana@example.invalid").andExpect(status().isOk())).path("user").path("taxpayerId").asLong();
        TaxpayerReference created=taxpayers.findById(id).orElseThrow();
        assertThat(created.taxpayerType).isEqualTo(TaxpayerType.CITIZEN);
        assertThat(created.canonicalCitizenId).isEqualTo(M1_CITIZEN.get());
        assertThat(created.externalId).isEqualTo(M1_CITIZEN.get().toString());
        assertThat(created.dni).isEqualTo("31415926");
        assertThat(created.cuit).isEqualTo("20314159263");
        assertThat(created.displayName).isEqualTo("Ana Paz");
        assertThat(created.externalStatus).isEqualTo(TaxpayerStatus.ACTIVE);
        assertThat(audit.findAll()).anyMatch(e->"TAXPAYER_REGISTERED_FROM_M1_LOGIN".equals(e.action)&&M1_CITIZEN.get().toString().equals(e.userId));
        login("ana@example.invalid").andExpect(status().isOk()).andExpect(jsonPath("$.user.taxpayerId").value(id));
        assertThat(taxpayers.count()).isEqualTo(references+1);
    }

    @Test void firstLoginDoesNotTakeOverAnotherReference() throws Exception {
        TaxpayerReference legacy=taxpayer(null,TaxpayerStatus.ACTIVE);
        legacy.dni="31415926";taxpayers.saveAndFlush(legacy);
        M1_PROFILE.set(PROFILE);
        long references=taxpayers.count();
        login("ana@example.invalid").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("M1_CITIZEN_NOT_LINKED"));
        assertThat(taxpayers.count()).isEqualTo(references);
        assertThat(taxpayers.findById(legacy.id).orElseThrow().canonicalCitizenId).isNull();
    }

    @Test void citizenWithoutLocalReferenceIsRejectedAndAudited() throws Exception {
        login("sin.vinculo@example.invalid").andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("M1_CITIZEN_NOT_LINKED"));
        assertThat(audit.findAll()).anyMatch(e->"M1_LOGIN_REJECTED".equals(e.action)&&M1_CITIZEN.get().toString().equals(e.userId));
    }

    @Test void blockedOrDeceasedCitizenCannotLogIn() throws Exception {
        taxpayer(M1_CITIZEN.get(),TaxpayerStatus.DECEASED);
        login("fallecido@example.invalid").andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("M1_TAXPAYER_INACTIVE"));
    }

    @Test void blockingTheTaxpayerEndsAnOpenSession() throws Exception {
        TaxpayerReference own=taxpayer(M1_CITIZEN.get(),TaxpayerStatus.ACTIVE);
        String bearer="Bearer "+read(login("ana@example.invalid").andExpect(status().isOk())).path("token").asText();
        own.externalStatus=TaxpayerStatus.BLOCKED;taxpayers.saveAndFlush(own);
        mvc.perform(get("/api/v1/taxpayers/{id}/debts",own.id).header("Authorization",bearer))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test void expiredUnknownOrMissingBearerIsUnauthorized() throws Exception {
        TaxpayerReference own=taxpayer(M1_CITIZEN.get(),TaxpayerStatus.ACTIVE);
        String bearer="Bearer "+read(login("ana@example.invalid").andExpect(status().isOk())).path("token").asText();
        CitizenSession session=sessions.findAll().stream().filter(s->s.taxpayerId.equals(own.id)).findFirst().orElseThrow();
        session.createdAt=OffsetDateTime.now().minusHours(2);session.expiresAt=OffsetDateTime.now().minusMinutes(1);sessions.saveAndFlush(session);
        mvc.perform(get("/api/v1/taxpayers/{id}/debts",own.id).header("Authorization",bearer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/taxpayers/{id}/debts",own.id).header("Authorization","Bearer desconocido")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/m1/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/m1/logout").header("Authorization","Basic eDp5")).andExpect(status().isUnauthorized());
    }

    @Test void m1RejectionsKeepTheirCodeAndCreateNoSession() throws Exception {
        long before=sessions.count();
        M1_STATUS.set(401);
        login("ana@example.invalid").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        M1_STATUS.set(503);
        login("ana@example.invalid").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("M1_UNAVAILABLE"));
        assertThat(sessions.count()).isEqualTo(before);
    }

    @Test void loginValidatesCredentialsBeforeCallingM1() throws Exception {
        mvc.perform(post("/api/v1/auth/m1/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email","no-es-correo","password","x")))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/m1/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email","ana@example.invalid")))).andExpect(status().isBadRequest());
    }

    @Test void credentialsAreRedactedFromToString() {
        assertThat(new M1CitizenAuthService.LoginRequest("ana@example.invalid","secreta").toString()).doesNotContain("secreta","ana@");
        assertThat(new M1CitizenAuthService.LoginResponse("token-opaco",null).toString()).doesNotContain("token-opaco");
    }

    @Test void bearerTokenParsing() {
        assertThat(M1CitizenAuthService.bearerToken("bearer abc ")).isEqualTo("abc");
        assertThat(M1CitizenAuthService.bearerToken("Bearer ")).isNull();
        assertThat(M1CitizenAuthService.bearerToken("Basic abc")).isNull();
        assertThat(M1CitizenAuthService.bearerToken(null)).isNull();
    }

    private ResultActions login(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/m1/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email",email,"password","clave-fixture"))));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private TaxpayerReference taxpayer(UUID citizenId,TaxpayerStatus status) {
        TaxpayerReference taxpayer=new TaxpayerReference();
        taxpayer.taxpayerType=TaxpayerType.CITIZEN;taxpayer.externalId="M1-"+UUID.randomUUID();taxpayer.canonicalCitizenId=citizenId;
        taxpayer.displayName="Ana Paz";taxpayer.externalStatus=status;taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();
        return taxpayers.saveAndFlush(taxpayer);
    }

    private static M1AuthClientTests.StubM1 stub() {
        try {
            return new M1AuthClientTests.StubM1(exchange->{
                String citizen=M1_CITIZEN.get().toString();
                M1AuthClientTests.respondLogin(exchange,M1_STATUS.get(),
                    "{\"citizenId\":\""+citizen+"\",\"actorId\":\""+citizen+"\",\"actorType\":\"CIUDADANO\",\"auth\":true"+M1_PROFILE.get()+"}");
            });
        } catch(IOException ex) { throw new UncheckedIOException(ex); }
    }
}
