package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test") @SpringBootTest @AutoConfigureMockMvc @Transactional
@org.springframework.test.context.jdbc.Sql(statements="INSERT INTO demo_bootstrap_lock(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM demo_bootstrap_lock WHERE id=1)")
class SecurityTests {
    @Autowired MockMvc mvc; @Autowired TaxpayerRepository taxpayers; @Autowired BillRepository bills;
    @Autowired ObjectMapper json; @Autowired DemoUserRepository users; @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth; @Autowired DemoAuthSessionRepository sessions; @Autowired AuditRepository audit;
    @Autowired PaymentRepository payments; @Autowired PaymentAllocationRepository allocations;
    @Autowired OutboxRepository outbox;
    private DemoAuthSessions demo;

    @BeforeEach void sessions() {
        demo=new DemoAuthSessions(users,taxpayers,encoder,demoAuth);
    }

    @Test void borrarLiquidacionDevuelve405ConMetodosPermitidos() throws Exception {
        mvc.perform(delete("/api/v1/liquidations/1").header("X-Demo-Session",demo.token(DemoRole.RENTAS)))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            .andExpect(header().string("Allow",org.hamcrest.Matchers.containsString("GET")));
    }

    @Test void cabeceraIdempotenciaDevuelveElMismoPagoYRechazaOtroContenido() throws Exception {
        TaxpayerReference t=new TaxpayerReference();t.taxpayerType=TaxpayerType.CITIZEN;t.externalId="QA-IDEM";t.displayName="QA";
        t.externalStatus=TaxpayerStatus.ACTIVE;t.createdAt=t.updatedAt=OffsetDateTime.now();taxpayers.saveAndFlush(t);
        String body="{\"taxpayerId\":"+t.id+",\"paymentMethod\":\"CASH\",\"amount\":10,\"allocations\":[]}";
        String session=demo.token(DemoRole.RENTAS);
        var primero=mvc.perform(post("/api/v1/payments").header("X-Demo-Session",session).header("Idempotency-Key","qa-http")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        long id=json.readTree(primero.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(post("/api/v1/payments").header("X-Demo-Session",session).header("Idempotency-Key","qa-http")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/v1/payments").header("X-Demo-Session",session).header("Idempotency-Key","qa-http")
            .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"amount\":10","\"amount\":11")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(payments.findByTaxpayerId(t.id)).hasSize(1);
        assertThat(allocations.findByPaymentId(id)).isEmpty();
        assertThat(outbox.findAll().stream().filter(x->"paymentRegistered".equals(x.eventType)&&String.valueOf(id).equals(x.aggregateId))).hasSize(1);
        assertThat(audit.findByEntityTypeAndEntityIdOrderByOccurredAt("Payment",String.valueOf(id))).hasSize(1);
    }

    @Test void auditorCannotWrite() throws Exception {
        mvc.perform(post("/api/v1/tax-concepts").header("X-Demo-Session",demo.token(DemoRole.AUDITOR)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"X\",\"name\":\"X\",\"type\":\"FEE\",\"originModule\":\"M5\"}"))
            .andExpect(status().isForbidden());
    }

    @Test void rentasCanReachPaymentRegistrationFromItsOperationalScreen() throws Exception {
        mvc.perform(post("/api/v1/payments").header("X-Demo-Session",demo.token(DemoRole.RENTAS)).contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test void taxpayerCanReadTheConceptCatalogRequiredByExemptions() throws Exception {
        mvc.perform(get("/api/v1/tax-concepts").header("X-Demo-Session",demo.token(DemoRole.TAXPAYER)))
            .andExpect(status().isOk());
    }

    @Test void taxpayerCannotReadAnotherTaxpayer() throws Exception {
        TaxpayerReference owner=taxpayer("SEC-1");TaxpayerReference other=taxpayer("SEC-2");
        String session=demo.token(DemoRole.TAXPAYER,owner.id);
        mvc.perform(get("/api/v1/taxpayers/{id}/summary",owner.id).header("X-Demo-Session",session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxpayers/{id}/summary",other.id).header("X-Demo-Session",session))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
        mvc.perform(get("/api/v1/taxpayers/{id}/summary",other.id).header("X-Demo-Session",session)
                .header("X-Dev-Roles","SUPERVISOR").header("X-Dev-Taxpayer-Id",other.id).header("X-Dev-User","forged"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
    }

    @Test void taxpayerCannotReadAnotherTaxpayersBill() throws Exception {
        TaxpayerReference owner=taxpayer("SEC-BILL");TaxpayerReference stranger=taxpayer("SEC-BILL-X");
        Bill bill=new Bill();bill.number="SEC-BILL-1";bill.taxpayerId=owner.id;bill.totalAmount=java.math.BigDecimal.TEN;bill.issueDate=java.time.LocalDate.now();bill.dueDate=java.time.LocalDate.now().plusDays(1);bill.status=BillStatus.ISSUED;bill.createdBy="test";bill.createdAt=OffsetDateTime.now();bills.save(bill);
        mvc.perform(get("/api/v1/bills/{id}",bill.id).header("X-Demo-Session",demo.token(DemoRole.TAXPAYER,stranger.id)))
            .andExpect(status().isForbidden());
    }

    @Test void missingSessionDoesNotGrantPrivilegedIdentity() throws Exception {
        mvc.perform(get("/api/v1/tax-concepts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tax-concepts").header("X-Dev-User","dev-rentas").header("X-Dev-Roles","RENTAS,SUPERVISOR,CASHIER"))
            .andExpect(status().isUnauthorized());
    }

    @Test void loginIssuesRandomOpaqueTokenAndRejectsInactiveUser() throws Exception {
        DemoUser user=persist(DemoRole.RENTAS,null,true);
        var first=json.readTree(mvc.perform(post("/api/v1/dev-auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\""+user.username+"\",\"password\":\""+DemoAuthSessions.PASSWORD+"\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var second=json.readTree(mvc.perform(post("/api/v1/dev-auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\""+user.username+"\",\"password\":\""+DemoAuthSessions.PASSWORD+"\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.path("token").asText()).isNotBlank().isNotEqualTo("dev-session").isNotEqualTo(second.path("token").asText());
        assertThat(sessions.findByTokenHash(DemoAuthService.hashToken(first.path("token").asText()))).isPresent();
        user.active=false;users.save(user);
        mvc.perform(post("/api/v1/dev-auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\""+user.username+"\",\"password\":\""+DemoAuthSessions.PASSWORD+"\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(get("/api/v1/dev-auth/me").header("X-Demo-Session",first.path("token").asText()))
            .andExpect(status().isUnauthorized());
    }

    @Test void expiredAndRevokedSessionsAreRejected() throws Exception {
        String token=demo.token(DemoRole.RENTAS);
        DemoAuthSession session=sessions.findByTokenHash(DemoAuthService.hashToken(token)).orElseThrow();
        session.createdAt=OffsetDateTime.now().minusHours(2);session.expiresAt=OffsetDateTime.now().minusMinutes(1);sessions.save(session);
        mvc.perform(get("/api/v1/dev-auth/me").header("X-Demo-Session",token)).andExpect(status().isUnauthorized());
        String live=demoAuth.login(new DemoAuthController.LoginRequest(
            users.findById(session.demoUserId).orElseThrow().username,DemoAuthSessions.PASSWORD)).token();
        mvc.perform(post("/api/v1/dev-auth/logout").header("X-Demo-Session",live)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/dev-auth/me").header("X-Demo-Session",live)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/dev-auth/logout").header("X-Demo-Session",live)).andExpect(status().isUnauthorized());
    }

    @Test void bootstrapCreatesOnlyTheFirstSupervisorOnce() throws Exception {
        sessions.deleteAll();
        users.deleteAll();
        mvc.perform(post("/api/v1/dev-auth/bootstrap").header("X-Demo-Bootstrap-Secret","test-bootstrap-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"qa.supervisor\",\"password\":\"clave-segura\",\"displayName\":\"QA Supervisor\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("SUPERVISOR")).andExpect(jsonPath("$.username").value("qa.supervisor"));
        DemoUser stored=users.findByUsernameIgnoreCase("qa.supervisor").orElseThrow();
        assertThat(stored.passwordHash).startsWith("$2").doesNotContain("clave-segura");
        mvc.perform(post("/api/v1/dev-auth/bootstrap").header("X-Demo-Bootstrap-Secret","test-bootstrap-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"otro.supervisor\",\"password\":\"clave-segura\",\"displayName\":\"Otro\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEMO_BOOTSTRAP_ALREADY_COMPLETED"));
        assertThat(users.count()).isEqualTo(1);
    }

    @Test void corsAllowsConfiguredOriginAndRequiredHeaders() throws Exception {
        mvc.perform(options("/api/v1/health").header("Origin","http://localhost:5173")
                .header("Access-Control-Request-Method","GET")
                .header("Access-Control-Request-Headers","content-type,x-demo-session,idempotency-key,x-correlation-id,authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin","http://localhost:5173"))
            .andExpect(header().string("Access-Control-Allow-Headers",org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsStringIgnoringCase("X-Demo-Session"),
                org.hamcrest.Matchers.containsStringIgnoringCase("Idempotency-Key"))));
        mvc.perform(options("/api/v1/health").header("Origin","https://evil.example")
                .header("Access-Control-Request-Method","GET"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test void healthAndOpenApiArePublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        String openApi=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var paths=json.readTree(openApi).path("paths");int operations=0;for(var path=paths.fields();path.hasNext();)operations+=path.next().getValue().size();
        org.assertj.core.api.Assertions.assertThat(operations).isEqualTo(149);
        org.assertj.core.api.Assertions.assertThat(paths.size()).isEqualTo(134);
        org.assertj.core.api.Assertions.assertThat(paths.fieldNames()).toIterable().noneMatch(x->x.startsWith("/events/"));
        org.assertj.core.api.Assertions.assertThat(paths.has("/api/v1/dev-auth/bootstrap")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.has("/api/v1/dev-auth/logout")).isTrue();
    }

    private TaxpayerReference taxpayer(String external) {
        TaxpayerReference t=new TaxpayerReference();t.taxpayerType=TaxpayerType.CITIZEN;t.externalId=external;t.displayName=external;
        t.externalStatus=TaxpayerStatus.ACTIVE;t.createdAt=t.updatedAt=OffsetDateTime.now();return taxpayers.save(t);
    }

    private DemoUser persist(DemoRole role,Long taxpayerId,boolean active) {
        DemoUser user=new DemoUser();
        user.username=("sec."+role.name()+"."+java.util.UUID.randomUUID()).toLowerCase();
        user.passwordHash=encoder.encode(DemoAuthSessions.PASSWORD);
        user.displayName="QA";user.role=role;user.taxpayerId=taxpayerId;user.active=active;
        user.createdAt=user.updatedAt=OffsetDateTime.now();return users.save(user);
    }
}
