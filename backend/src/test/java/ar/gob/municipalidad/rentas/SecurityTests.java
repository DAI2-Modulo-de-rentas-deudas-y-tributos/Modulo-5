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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Autowired TicketCaseRepository ticketCases; @Autowired TicketCaseUpdateRepository ticketUpdates;
    private DemoAuthSessions demo;

    @BeforeEach void sessions() {
        demo=new DemoAuthSessions(users,taxpayers,encoder,demoAuth);
    }

    @Test void borrarLiquidacionDevuelve405ConMetodosPermitidos() throws Exception {
        mvc.perform(delete("/api/v1/liquidations/1").header("X-Demo-Session",demo.token(DemoRole.RENTAS)))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
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
        String traceId="audit-denied-auditor-"+java.util.UUID.randomUUID();
        mvc.perform(post("/api/v1/tax-concepts").header("X-Demo-Session",demo.token(DemoRole.AUDITOR)).header("X-Correlation-Id",traceId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"X\",\"name\":\"X\",\"type\":\"FEE\",\"originModule\":\"M5\"}"))
            .andExpect(status().isForbidden());
        assertThat(audit.findAll().stream().filter(entry->traceId.equals(entry.correlationId)).toList()).singleElement().satisfies(entry -> {
            assertThat(entry.entityType).isEqualTo("SecurityEvent");
            assertThat(entry.action).isEqualTo("ACCESS_DENIED");
            assertThat(entry.userRole).contains("AUDITOR");
            assertThat(entry.newData).contains("/api/v1/tax-concepts").doesNotContain("X-Demo-Session");
        });
    }

    @Test void securityEventsAreFilteredRedactedPaginatedAndLimitedToAuditorAndSupervisor() throws Exception {
        String actor="security-query-"+java.util.UUID.randomUUID();
        String secret="SENSITIVE-SECRET-"+java.util.UUID.randomUUID();
        String personalData="SENSITIVE-PII-"+java.util.UUID.randomUUID();
        OffsetDateTime firstAt=OffsetDateTime.now().minusMinutes(2),secondAt=firstAt.plusMinutes(1);
        AuditEntry first=securityAudit(actor,firstAt,secret,personalData);audit.saveAndFlush(first);
        AuditEntry second=securityAudit(actor,secondAt,"SECOND-SECRET","SECOND-PII");audit.saveAndFlush(second);
        AuditEntry unrelated=new AuditEntry();unrelated.entityType="Payment";unrelated.entityId="1";
        unrelated.action=first.action;unrelated.userId=actor;unrelated.userRole=first.userRole;unrelated.occurredAt=firstAt;
        unrelated.newData="{\"paymentReference\":\"unrelated\"}";audit.saveAndFlush(unrelated);

        for(DemoRole role:java.util.List.of(DemoRole.AUDITOR,DemoRole.SUPERVISOR)) {
            String session=demo.token(role);
            var firstPage=mvc.perform(get("/api/v1/audit").header("X-Demo-Session",session)
                    .param("entityType","SecurityEvent").param("action",first.action).param("userId",actor)
                    .param("page","0").param("size","1").param("sort","occurredAt,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first.id))
                .andExpect(jsonPath("$.content[0].entityType").value("SecurityEvent"))
                .andExpect(jsonPath("$.content[0].action").value("DEMO_LOGIN_FAILED"))
                .andExpect(jsonPath("$.content[0].userId").value(actor))
                .andExpect(jsonPath("$.content[0].previousData").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].newData").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.page.number").value(0)).andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2)).andExpect(jsonPath("$.page.totalPages").value(2))
                .andReturn();
            String firstJson=firstPage.getResponse().getContentAsString();
            assertThat(firstJson).doesNotContain(secret,personalData,"SECOND-SECRET","SECOND-PII");

            var secondPage=mvc.perform(get("/api/v1/audit").header("X-Demo-Session",session)
                    .param("entityType","SecurityEvent").param("action",first.action).param("userId",actor)
                    .param("page","1").param("size","1").param("sort","occurredAt,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(second.id)).andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2)).andReturn();
            assertThat(secondPage.getResponse().getContentAsString()).doesNotContain("SECOND-SECRET","SECOND-PII");

            mvc.perform(get("/api/v1/audit/{id}",first.id).header("X-Demo-Session",session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.previousData").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.newData").value(org.hamcrest.Matchers.nullValue()));
            mvc.perform(get("/api/v1/audit/entities/SecurityEvent/{id}",first.entityId).header("X-Demo-Session",session))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].previousData").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].newData").value(org.hamcrest.Matchers.nullValue()));
        }
        for(DemoRole role:java.util.List.of(DemoRole.RENTAS,DemoRole.CASHIER,DemoRole.TAXPAYER))
            mvc.perform(get("/api/v1/audit").header("X-Demo-Session",demo.token(role)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/audit").header("X-Demo-Session",demo.token(DemoRole.AUDITOR))
                .param("entityType","Payment").param("action",first.action).param("userId",actor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].entityType").value("Payment"))
            .andExpect(jsonPath("$.content[0].newData").value(unrelated.newData));
    }

    private AuditEntry securityAudit(String actor,OffsetDateTime occurredAt,String secret,String personalData) {
        AuditEntry event=new AuditEntry();event.entityType="SecurityEvent";event.entityId=java.util.UUID.randomUUID().toString();
        event.action="DEMO_LOGIN_FAILED";event.userId=actor;event.userRole="ANONYMOUS";event.occurredAt=occurredAt;
        event.previousData="{\"password\":\""+secret+"\"}";
        event.newData="{\"nationalId\":\""+personalData+"\",\"token\":\""+secret+"\"}";
        return event;
    }

    @Test void auditorCanReadTicketsButCannotChangeThem() throws Exception {
        TicketCase ticket=new TicketCase();ticket.externalTicketId="SCRUM-820-"+java.util.UUID.randomUUID();ticket.category="CONSULTA";ticket.description="Ticket sintético para validar acceso de lectura";ticket.priority=TicketPriority.MEDIUM;ticket.status=TicketCaseStatus.OPEN;ticket.createdAt=ticket.updatedAt=OffsetDateTime.now();
        ticket=ticketCases.saveAndFlush(ticket);
        long ticketId=ticket.id;
        String auditor=demo.token(DemoRole.AUDITOR);

        var list=mvc.perform(get("/api/v1/tickets").header("X-Demo-Session",auditor))
            .andExpect(status().isOk()).andReturn();
        var content=json.readTree(list.getResponse().getContentAsString()).path("content");
        boolean included=false;
        for(JsonNode row:content) if(row.path("id").asLong()==ticketId) included=true;
        assertThat(included).isTrue();
        mvc.perform(get("/api/v1/tickets/{id}",ticketId).header("X-Demo-Session",auditor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ticketId));
        mvc.perform(get("/api/v1/tickets/{id}",ticketId).header("X-Demo-Session",demo.token(DemoRole.RENTAS)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ticketId));
        mvc.perform(get("/api/v1/tickets/{id}",ticketId).header("X-Demo-Session",demo.token(DemoRole.TAXPAYER)))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/tickets/{id}/assign",ticketId).header("X-Demo-Session",auditor)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tickets/{id}/updates",ticketId).header("X-Demo-Session",auditor).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"nota\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tickets/{id}/request-information",ticketId).header("X-Demo-Session",auditor).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"información\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tickets/{id}/return",ticketId).header("X-Demo-Session",auditor).contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"NEEDS_INFO\",\"internalMessage\":\"revisar\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tickets/{id}/complete",ticketId).header("X-Demo-Session",auditor).contentType(MediaType.APPLICATION_JSON).content("{\"resolution\":\"resuelto\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tickets/{id}/reject",ticketId).header("X-Demo-Session",auditor).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no corresponde\"}")).andExpect(status().isForbidden());

        assertThat(ticketCases.findById(ticketId).orElseThrow().status).isEqualTo(TicketCaseStatus.OPEN);
        assertThat(ticketUpdates.findByTicketCaseIdOrderByCreatedAt(ticketId)).isEmpty();
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

    @Test void taxpayerCannotPageAnotherTaxpayersDebts() throws Exception {
        TaxpayerReference owner=taxpayer("SEC-DEBT-1");TaxpayerReference other=taxpayer("SEC-DEBT-2");
        String session=demo.token(DemoRole.TAXPAYER,owner.id);
        mvc.perform(get("/api/v1/taxpayers/{id}/debts",owner.id).header("X-Demo-Session",session))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxpayers/{id}/debts",other.id).header("X-Demo-Session",session))
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

    @Test void demoLoginAndLogoutArePersistentlyAuditedWithoutCredentialMaterial() throws Exception {
        DemoUser user=persist(DemoRole.RENTAS,null,true);
        String body="{\"username\":\""+user.username+"\",\"password\":\""+DemoAuthSessions.PASSWORD+"\"}";
        JsonNode login=json.readTree(mvc.perform(post("/api/v1/dev-auth/login").with(request->{request.setRemoteAddr("198.51.100.31");return request;}).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String token=login.path("token").asText();
        AuditEntry success=audit.findAll().stream().filter(entry->entry.action.equals("DEMO_LOGIN_SUCCEEDED")&&entry.userId.equals(user.integrationActorId.toString())).findFirst().orElseThrow();
        assertThat(success.userRole).isEqualTo("RENTAS");
        assertThat(success.newData).contains("SUCCESS","198.51.100.31").doesNotContain(token,DemoAuthSessions.PASSWORD);

        String wrongPassword="wrong-password-not-for-audit";
        mvc.perform(post("/api/v1/dev-auth/login").with(request->{request.setRemoteAddr("198.51.100.32");return request;}).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\""+user.username+"\",\"password\":\""+wrongPassword+"\"}"))
            .andExpect(status().isUnauthorized());
        AuditEntry failure=audit.findAll().stream().filter(entry->entry.action.equals("DEMO_LOGIN_FAILED")&&entry.newData.contains("198.51.100.32")).findFirst().orElseThrow();
        assertThat(failure.userId).isEqualTo(user.integrationActorId.toString());
        assertThat(failure.userRole).isEqualTo("RENTAS");
        assertThat(failure.newData).contains("INVALID_CREDENTIALS","198.51.100.32").doesNotContain(user.username,wrongPassword,DemoAuthSessions.PASSWORD,token);

        mvc.perform(post("/api/v1/dev-auth/logout").with(request->{request.setRemoteAddr("198.51.100.33");return request;}).header("X-Demo-Session",token)).andExpect(status().isNoContent());
        AuditEntry logout=audit.findAll().stream().filter(entry->entry.action.equals("DEMO_LOGOUT_SUCCEEDED")&&entry.userId.equals(user.integrationActorId.toString())).findFirst().orElseThrow();
        assertThat(logout.newData).contains("SUCCESS","198.51.100.33").doesNotContain(token,DemoAuthSessions.PASSWORD);
    }

    @Test void ownership403IsPersistedWithoutQueryStringOrSensitiveValues() throws Exception {
        TaxpayerReference owner=taxpayer("SEC-AUDIT-OWNER");
        TaxpayerReference other=taxpayer("SEC-AUDIT-OTHER");
        String session=demo.token(DemoRole.TAXPAYER,owner.id);
        String traceId="audit-denied-owner-"+java.util.UUID.randomUUID();
        mvc.perform(get("/api/v1/taxpayers/{id}/summary?probe=secret-value",other.id).with(request->{request.setRemoteAddr("198.51.100.44");return request;}).header("X-Demo-Session",session).header("X-Correlation-Id",traceId))
            .andExpect(status().isForbidden());
        AuditEntry denied=audit.findAll().stream().filter(entry->entry.action.equals("ACCESS_DENIED")&&traceId.equals(entry.correlationId)).findFirst().orElseThrow();
        assertThat(denied.userId).isNotEqualTo("anonymous");
        assertThat(denied.userRole).contains("TAXPAYER");
        assertThat(denied.newData).contains("FORBIDDEN_OWNERSHIP","/api/v1/taxpayers","198.51.100.44","GET /api/v1/taxpayers")
            .doesNotContain("/"+other.id+"/","probe","secret-value",session);
    }

    @Test void securityEventsCannotBeUpdatedThroughJpa() throws Exception {
        DemoUser user=persist(DemoRole.RENTAS,null,true);
        mvc.perform(post("/api/v1/dev-auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\""+user.username+"\",\"password\":\""+DemoAuthSessions.PASSWORD+"\"}"))
            .andExpect(status().isOk());
        AuditEntry event=audit.findAll().stream().filter(entry->"SecurityEvent".equals(entry.entityType)
            &&"DEMO_LOGIN_SUCCEEDED".equals(entry.action)&&user.integrationActorId.toString().equals(entry.userId)).findFirst().orElseThrow();
        event.entityType="Audit";
        event.action="TAMPERED";
        assertThatThrownBy(()->audit.flush()).hasStackTraceContaining("SecurityEvent entries are append-only");
    }

    @Test void securityEventsCannotBeDeletedThroughJpa() throws Exception {
        DemoUser user=persist(DemoRole.RENTAS,null,true);
        mvc.perform(post("/api/v1/dev-auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\""+user.username+"\",\"password\":\""+DemoAuthSessions.PASSWORD+"\"}"))
            .andExpect(status().isOk());
        AuditEntry event=audit.findAll().stream().filter(entry->"SecurityEvent".equals(entry.entityType)
            &&"DEMO_LOGIN_SUCCEEDED".equals(entry.action)&&user.integrationActorId.toString().equals(entry.userId)).findFirst().orElseThrow();
        assertThatThrownBy(()->{audit.delete(event);audit.flush();}).hasStackTraceContaining("SecurityEvent entries are append-only");
    }

    @Test void apiSecurityHeadersAndSwaggerRemainUsable() throws Exception {
        mvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options","nosniff"))
            .andExpect(header().string("X-Frame-Options","DENY"))
            .andExpect(header().string("Referrer-Policy","no-referrer"))
            .andExpect(header().doesNotExist("Strict-Transport-Security"));
        mvc.perform(get("/api/v1/health").secure(true))
            .andExpect(status().isOk())
            .andExpect(header().exists("Strict-Transport-Security"));
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(header().string("Referrer-Policy","no-referrer"));
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

    @Test void demoAuthHttpResponsesExposeOnlyTheDesignedLoginTokenAndNeverCredentialMaterial() throws Exception {
        sessions.deleteAll();
        users.deleteAll();
        String bootstrapSecret="test-bootstrap-secret";
        String bootstrapPassword="bootstrap-pass-ok";
        String createdPassword="created-pass-ok";
        JsonNode bootstrap=json.readTree(mvc.perform(post("/api/v1/dev-auth/bootstrap")
                .header("X-Demo-Bootstrap-Secret",bootstrapSecret).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"qa.supervisor\",\"password\":\""+bootstrapPassword+"\",\"displayName\":\"QA Supervisor\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertSafeDemoUserResponse(bootstrap,bootstrapSecret,bootstrapPassword);

        JsonNode login=json.readTree(mvc.perform(post("/api/v1/dev-auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"qa.supervisor\",\"password\":\""+bootstrapPassword+"\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String token=login.path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(login.path("user").has("token")).isFalse();
        ObjectNode loginWithoutToken=((ObjectNode)login).deepCopy();
        loginWithoutToken.remove("token");
        assertNoCredentialFields(loginWithoutToken,bootstrapSecret,bootstrapPassword);
        DemoUser supervisor=users.findByUsernameIgnoreCase("qa.supervisor").orElseThrow();
        assertThat(supervisor.passwordHash).startsWith("$2");
        assertThat(encoder.matches(bootstrapPassword,supervisor.passwordHash)).isTrue();
        assertThat(supervisor.passwordHash).isNotEqualTo(bootstrapPassword);
        DemoAuthSession storedSession=sessions.findByTokenHash(DemoAuthService.hashToken(token)).orElseThrow();
        assertThat(storedSession.tokenHash).isNotEqualTo(token).matches("[0-9a-f]{64}");

        String supervisorToken=token;
        JsonNode created=json.readTree(mvc.perform(post("/api/v1/dev-auth/users").header("X-Demo-Session",supervisorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"qa.rentas\",\"password\":\""+createdPassword+"\",\"displayName\":\"QA Rentas\",\"role\":\"RENTAS\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertSafeDemoUserResponse(created,bootstrapSecret,createdPassword);
        DemoUser rentas=users.findByUsernameIgnoreCase("qa.rentas").orElseThrow();
        assertThat(rentas.passwordHash).startsWith("$2");
        assertThat(encoder.matches(createdPassword,rentas.passwordHash)).isTrue();
        assertThat(rentas.passwordHash).isNotEqualTo(createdPassword);

        JsonNode listed=json.readTree(mvc.perform(get("/api/v1/dev-auth/users").header("X-Demo-Session",supervisorToken))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertNoCredentialFields(listed,bootstrapSecret,bootstrapPassword,createdPassword);
        JsonNode me=json.readTree(mvc.perform(get("/api/v1/dev-auth/me").header("X-Demo-Session",supervisorToken))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertSafeDemoUserResponse(me,bootstrapSecret,bootstrapPassword);
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
        var errorSchema=json.readTree(openApi).path("components").path("schemas").path("ErrorResponse");
        org.assertj.core.api.Assertions.assertThat(errorSchema.path("properties").has("code")).isTrue();
        org.assertj.core.api.Assertions.assertThat(errorSchema.path("properties").has("message")).isTrue();
        org.assertj.core.api.Assertions.assertThat(errorSchema.path("properties").has("details")).isTrue();
        org.assertj.core.api.Assertions.assertThat(errorSchema.path("properties").has("traceId")).isTrue();
        var paths=json.readTree(openApi).path("paths");int operations=0;for(var path=paths.fields();path.hasNext();){var methods=path.next().getValue().fields();while(methods.hasNext()){String method=methods.next().getKey();if(java.util.Set.of("get","post","put","patch","delete","options","head","trace").contains(method))operations++;}}
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/tax-concepts").path("post").path("responses")
            .path("400").path("$ref").asText()).isEqualTo("#/components/responses/ApiError");
        org.assertj.core.api.Assertions.assertThat(operations).isEqualTo(169);
        org.assertj.core.api.Assertions.assertThat(paths.size()).isEqualTo(153);
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/administration/process-due-dates/{id}").has("get")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/taxpayers/{id}/canonical-citizen-link").has("post")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/tickets/{id}/return").has("post")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/integrations/deferred/exemptions/{requestId}/reconcile").has("post")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/integrations/deferred/payment-plans/{requestId}/reconcile").has("post")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/integrations/deferred/economic").has("get")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/integrations/deferred/economic/{deferralId}/reconcile").has("post")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/social-benefit-rules").has("get")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/social-benefit-rules").has("post")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/social-benefit-rules/{id}/submit").has("post")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/social-benefit-rules/{id}/approve").has("post")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/social-benefit-rules/{id}/disable").has("post")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.has("/api/v1/payment-plan-requests/{id}/debts")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.has("/api/v1/dashboards/supervisor")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.has("/api/v1/dashboards/rentas")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.has("/api/v1/credit-balances/{id}/applications")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.fieldNames()).toIterable().noneMatch(x->x.startsWith("/events/"));
        org.assertj.core.api.Assertions.assertThat(paths.has("/api/v1/debts/{id}/history")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.has("/api/v1/dev-auth/bootstrap")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.has("/api/v1/dev-auth/logout")).isTrue();
        org.assertj.core.api.Assertions.assertThat(paths.path("/api/v1/integrations/outbox/{id}/retry").has("post")).isTrue();
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

    private void assertSafeDemoUserResponse(JsonNode response,String... secrets) {
        assertNoCredentialFields(response,secrets);
        assertThat(response.has("token")).isFalse();
    }

    private void assertNoCredentialFields(JsonNode response,String... secrets) {
        assertThat(response.toString().toLowerCase(java.util.Locale.ROOT))
            .doesNotContain("password","hash","secret","\"token\"");
        for(String secret:secrets) assertThat(response.toString()).doesNotContain(secret);
    }
}
