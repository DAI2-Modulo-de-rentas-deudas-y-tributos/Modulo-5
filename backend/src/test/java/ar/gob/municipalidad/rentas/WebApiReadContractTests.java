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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WebApiReadContractTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;
    @Autowired DebtRepository debts;
    @Autowired DemoUserRepository demoUsers;
    @Autowired IntegrationEventLogRepository integrations;
    @Autowired OutboxRepository outbox;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;
    private DemoAuthSessions demo;
    private String staff;
    private String rentas;

    @BeforeEach void demoSessions() {
        demo=new DemoAuthSessions(demoUsers,taxpayers,encoder,demoAuth);
        staff=demo.token(DemoRole.SUPERVISOR);
        rentas=demo.token(DemoRole.RENTAS);
    }

    @Test void collectionEndpointsExposeConsistentPagedContracts() throws Exception {
        String[] paged = {
            "/api/v1/taxpayers", "/api/v1/tax-concepts", "/api/v1/tax-configurations",
            "/api/v1/liquidations", "/api/v1/liquidation-runs", "/api/v1/adjustments",
            "/api/v1/debts", "/api/v1/bills", "/api/v1/payments",
            "/api/v1/payment-allocations", "/api/v1/payments/unallocated", "/api/v1/credit-balances",
            "/api/v1/payment-reversals", "/api/v1/payment-plan-configurations",
            "/api/v1/payment-plan-requests", "/api/v1/payment-plans", "/api/v1/payment-plans/defaulted",
            "/api/v1/payment-plan-expirations", "/api/v1/refinancing-requests",
            "/api/v1/exemption-requests", "/api/v1/exemptions", "/api/v1/tickets",
            "/api/v1/social-benefits", "/api/v1/external-obligations",
            "/api/v1/external-obligations/errors", "/api/v1/audit",
            "/api/v1/integrations/events", "/api/v1/integrations/events/errors", "/api/v1/integrations/outbox"
        };
        for (String url : paged) {
            mvc.perform(get(url).param("size", "2").header("X-Demo-Session", staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page.size").value(2));
        }
    }

    @Test void integrationsBitacoraCombinesInboundAndOutboxWithFaithfulStatesAndPagination() throws Exception {
        OffsetDateTime now=OffsetDateTime.now();
        UUID inboundId=UUID.randomUUID(),outboundId=UUID.randomUUID();
        IntegrationEventLog inbound=new IntegrationEventLog();inbound.eventId=inboundId;inbound.externalEventId=inboundId.toString();
        inbound.eventType="scrum787Synthetic";inbound.sourceModule="m1";inbound.targetModule="rentas";inbound.direction=EventDirection.INBOUND;
        inbound.status=IntegrationEventStatus.PROCESSED;inbound.payload="{}";inbound.retryCount=0;inbound.occurredAt=now.minusMinutes(2);
        inbound.receivedAt=now.minusMinutes(1);inbound.processedAt=now;integrations.saveAndFlush(inbound);
        OutboxEvent outbound=new OutboxEvent();outbound.id=outboundId;outbound.eventType="scrum787Synthetic";outbound.targetModule="CORE";
        outbound.aggregateType="ExemptionRequest";outbound.aggregateId="test";outbound.payload="{\"eventId\":\""+outboundId+"\"}";
        outbound.status=OutboxStatus.CORE_ACCEPTED;outbound.retryCount=1;outbound.createdAt=now.minusMinutes(1);outbound.lastAttemptAt=now;outbound.publishedAt=now;outbox.saveAndFlush(outbound);

        mvc.perform(withStaff(get("/api/v1/integrations/events").param("eventType","scrum787Synthetic").param("size","1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(2)).andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(withStaff(get("/api/v1/integrations/events").param("eventType","scrum787Synthetic").param("direction","OUT")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.content[0].eventId").value(outboundId.toString()))
            .andExpect(jsonPath("$.content[0].direction").value("OUTBOUND"))
            .andExpect(jsonPath("$.content[0].status").value("CORE_ACCEPTED"))
            .andExpect(jsonPath("$.content[0].publishedAt").isNotEmpty())
            .andExpect(jsonPath("$.content[0].processedAt").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(withStaff(get("/api/v1/integrations/events").param("eventType","scrum787Synthetic").param("direction","IN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].eventId").value(inboundId.toString()))
            .andExpect(jsonPath("$.content[0].direction").value("INBOUND"))
            .andExpect(jsonPath("$.content[0].status").value("PROCESSED"));
        mvc.perform(withStaff(get("/api/v1/integrations/events").param("eventType","scrum787Synthetic").param("size","1").param("page","1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].eventId").value(inboundId.toString()));
        OutboxEvent retrying=new OutboxEvent();retrying.id=UUID.randomUUID();retrying.eventType="scrum787Retrying";retrying.targetModule="CORE";
        retrying.aggregateType="ExemptionRequest";retrying.aggregateId="test";retrying.payload="{}";retrying.status=OutboxStatus.FAILED;
        retrying.retryCount=2;retrying.createdAt=now;retrying.lastAttemptAt=now;outbox.saveAndFlush(retrying);
        mvc.perform(withStaff(get("/api/v1/integrations/events").param("eventType","scrum787Retrying").param("direction","OUT").param("status","RETRYING")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].status").value("FAILED"));
    }

    @Test void integrationsBitacoraValidatesFiltersAndSupportsDeterministicFilteringAndSorting() throws Exception {
        String base="/api/v1/integrations/events";
        for (var invalid : new String[][]{{"direction","sideways"},{"status","UNKNOWN"},{"unexpected","x"},{"sort","secret,asc"},{"from","not-a-date"}}) {
            var request=get(base).header("X-Demo-Session",staff).param(invalid[0],invalid[1]);
            mvc.perform(request).andExpect(status().isBadRequest());
        }
        mvc.perform(withStaff(get(base).param("direction","outbound").param("status","DLQ")
                .param("eventType","none").param("sourceModule","rentas").param("targetModule","CORE")
                .param("from","2026-01-01").param("to","2026-01-31").param("sort","eventType,asc")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
        mvc.perform(withStaff(get(base).param("direction","inbound").param("status","processed")
                .param("sourceModule","m1").param("targetModule","rentas").param("from","2026-01-01T00:00:00Z")
                .param("to","2026-01-31T23:59:59Z").param("sort","occurredAt,desc")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
        mvc.perform(withStaff(get(base).param("direction"," ").param("status"," ").param("eventType"," ")))
            .andExpect(status().isOk());
    }

    @Test void missingResourcesUseTheSameSafeErrorContract() throws Exception {
        String[] missing = {
            "/api/v1/taxpayers/999999", "/api/v1/tax-concepts/999999",
            "/api/v1/tax-configurations/999999", "/api/v1/liquidations/999999",
            "/api/v1/liquidation-runs/999999", "/api/v1/adjustments/999999",
            "/api/v1/debts/999999", "/api/v1/bills/999999", "/api/v1/payments/999999",
            "/api/v1/credit-balances/999999", "/api/v1/credit-balances/999999/applications", "/api/v1/payment-reversals/999999",
            "/api/v1/payment-plan-configurations/999999", "/api/v1/payment-plan-requests/999999",
            "/api/v1/payment-plans/999999", "/api/v1/payment-plan-expirations/999999",
            "/api/v1/refinancing-requests/999999", "/api/v1/exemption-requests/999999",
            "/api/v1/exemptions/999999", "/api/v1/tickets/999999",
            "/api/v1/social-benefits/999999", "/api/v1/external-obligations/999999",
            "/api/v1/audit/999999",
            "/api/v1/integrations/events/00000000-0000-0000-0000-000000000001"
        };
        for (String url : missing) {
            var result = mvc.perform(get(url).header("X-Demo-Session", staff).header("X-Correlation-Id","scrum-653-business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value(url))
                .andExpect(jsonPath("$.traceId").value("scrum-653-business"))
                .andExpect(jsonPath("$.details").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(header().string("X-Correlation-Id","scrum-653-business"))
                .andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception", "Hibernate", "SQL");
        }
    }

    @Test void malformedAndInvalidRequestsReturnStableClientErrors() throws Exception {
        mvc.perform(post("/api/v1/tax-concepts").header("X-Demo-Session",staff).header("X-Correlation-Id","scrum-653-malformed")
                .contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.timestamp").isNotEmpty())
            .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.path").value("/api/v1/tax-concepts"))
            .andExpect(jsonPath("$.traceId").value("scrum-653-malformed")).andExpect(jsonPath("$.details").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(header().string("X-Correlation-Id","scrum-653-malformed"));
        mvc.perform(post("/api/v1/tax-concepts").header("X-Demo-Session",staff).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"\",\"name\":\"\",\"type\":\"FEE\",\"originModule\":\"M5\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/tax-concepts").header("X-Demo-Session",staff).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"X\",\"name\":\"X\",\"type\":\"NOT_A_TYPE\",\"originModule\":\"M5\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void validationErrorsIncludeSafeFieldDetailsAndPreserveCorrelation() throws Exception {
        String traceId="scrum-653-correlation";
        var result=mvc.perform(post("/api/v1/tax-concepts").header("X-Demo-Session",staff)
                .header("X-Correlation-Id",traceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"\",\"name\":\"\",\"description\":\"do-not-echo\",\"type\":\"FEE\",\"originModule\":\"M5\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.timestamp").isNotEmpty())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.path").value("/api/v1/tax-concepts"))
            .andExpect(jsonPath("$.traceId").value(traceId))
            .andExpect(jsonPath("$.details.fieldErrors[0].field").value("code"))
            .andExpect(jsonPath("$.details.fieldErrors[0].messages[0]").isNotEmpty())
            .andExpect(jsonPath("$.details.fieldErrors[1].field").value("name"))
            .andExpect(header().string("X-Correlation-Id",traceId))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("do-not-echo","rejectedValue");
    }

    @Test void commandsAgainstMissingAggregatesReturnNotFoundWithoutSideEffects() throws Exception {
        String[] bodyless = {
            "/api/v1/tax-configurations/999999/submit", "/api/v1/tax-configurations/999999/approve",
            "/api/v1/liquidation-runs/999999/preview", "/api/v1/liquidation-runs/999999/submit",
            "/api/v1/liquidation-runs/999999/approve", "/api/v1/liquidation-runs/999999/execute",
            "/api/v1/adjustments/999999/approve", "/api/v1/payment-reversals/999999/approve",
            "/api/v1/payment-reversals/999999/execute", "/api/v1/payment-plan-requests/999999/grant",
            "/api/v1/payment-plan-expirations/999999/approve", "/api/v1/refinancing-requests/999999/grant",
            "/api/v1/refinancing-requests/999999/approve-exception",
            "/api/v1/exemption-requests/999999/start-review", "/api/v1/exemption-requests/999999/submit-resolution",
            "/api/v1/exemption-requests/999999/approve", "/api/v1/tickets/999999/assign",
            "/api/v1/external-obligations/999999/retry",
            "/api/v1/integrations/events/00000000-0000-0000-0000-000000000001/reprocess"
        };
        for (String url : bodyless) assertMissing(post(url));

        String[] withReason = {
            "/api/v1/tax-configurations/999999/reject", "/api/v1/tax-configurations/999999/deactivate",
            "/api/v1/liquidation-runs/999999/reject", "/api/v1/adjustments/999999/reject",
            "/api/v1/payment-reversals/999999/reject", "/api/v1/payment-plan-requests/999999/reject",
            "/api/v1/payment-plan-requests/999999/submit-exception",
            "/api/v1/payment-plan-expirations/999999/reject", "/api/v1/refinancing-requests/999999/reject",
            "/api/v1/refinancing-requests/999999/submit-exception",
            "/api/v1/refinancing-requests/999999/reject-exception",
            "/api/v1/exemption-requests/999999/request-documentation", "/api/v1/exemption-requests/999999/reject",
            "/api/v1/tickets/999999/updates", "/api/v1/tickets/999999/request-information",
            "/api/v1/tickets/999999/complete", "/api/v1/tickets/999999/reject"
        };
        for (String url : withReason) {
            try {
                assertMissing(post(url).contentType(MediaType.APPLICATION_JSON)
                    .content(url.endsWith("/complete") ? "{\"resolution\":\"motivo válido\"}" :
                        url.contains("request-documentation") || url.endsWith("/updates") || url.endsWith("request-information")
                            ? "{\"message\":\"motivo válido\"}" : "{\"reason\":\"motivo válido\"}"));
            } catch (AssertionError failure) {
                throw new AssertionError("Contrato 404 incumplido para " + url, failure);
            }
        }

        mvc.perform(post("/api/v1/payments/999999/reversal-requests")
                .header("X-Demo-Session",demo.token(DemoRole.CASHIER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo válido\"}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertMissing(post("/api/v1/payments/999999/allocations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"debtId\":1,\"amount\":10}"));
        assertMissing(post("/api/v1/credit-balances/999999/apply").contentType(MediaType.APPLICATION_JSON)
            .content("{\"debtId\":1,\"amount\":10}"));
        assertMissing(post("/api/v1/payment-plans/999999/expiration-requests").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"motivo válido\"}"));
        assertMissing(post("/api/v1/payment-plans/999999/refinancing-requests").contentType(MediaType.APPLICATION_JSON)
            .content("{\"installments\":3}"));
        assertMissing(post("/api/v1/adjustments").contentType(MediaType.APPLICATION_JSON)
            .content("{\"debtId\":999999,\"type\":\"DISCOUNT\",\"amount\":0.01,\"reason\":\"Ajuste mínimo\"}"));
    }

    private void assertMissing(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.header("X-Demo-Session",staff)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void coreRestWorkflowPreservesEconomicInvariantsAndResponseContracts() throws Exception {
        TaxpayerReference taxpayer=new TaxpayerReference();taxpayer.taxpayerType=TaxpayerType.CITIZEN;
        taxpayer.externalId="API-HARDENING";taxpayer.displayName="Contrato API";taxpayer.externalStatus=TaxpayerStatus.ACTIVE;
        taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();taxpayers.save(taxpayer);

        long conceptId=id(postJson("/api/v1/tax-concepts",
            "{\"code\":\"API-HARD\",\"name\":\"Tasa API\",\"type\":\"FEE\",\"originModule\":\"M5\"}",201));
        patchJson("/api/v1/tax-concepts/"+conceptId,
            "{\"name\":\"Tasa API endurecida\",\"description\":\"contrato\",\"active\":true}");
        mvc.perform(withStaff(get("/api/v1/tax-concepts/{id}",conceptId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Tasa API endurecida"));

        String validFrom=LocalDate.now().minusDays(1).toString();
        long configurationId=id(postJson("/api/v1/tax-configurations",
            "{\"taxConceptId\":"+conceptId+",\"calculationType\":\"FIXED\",\"fixedAmount\":100,"+
                "\"partialPaymentAllowed\":true,\"paymentPlanAllowed\":true,\"validFrom\":\""+validFrom+"\"}",201,rentas));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/tax-configurations/"+configurationId)
            .header("X-Demo-Session",rentas).contentType(MediaType.APPLICATION_JSON).content("{\"fixedAmount\":100.00}")).andExpect(status().isOk());
        postJson("/api/v1/tax-configurations/"+configurationId+"/submit",null,200,rentas);
        postJson("/api/v1/tax-configurations/"+configurationId+"/approve",null,200);
        mvc.perform(withStaff(get("/api/v1/tax-configurations/{id}",configurationId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        String period=YearMonth.now().toString();String due=LocalDate.now().plusDays(30).toString();
        String liquidation="{\"taxpayerId\":"+taxpayer.id+",\"taxConceptId\":"+conceptId+",\"period\":\""+period+
            "\",\"taxableBase\":0,\"dueDate\":\""+due+"\"}";
        postJson("/api/v1/liquidations/preview",liquidation,200);
        long liquidationId=id(postJson("/api/v1/liquidations",liquidation,201));
        mvc.perform(withStaff(get("/api/v1/liquidations/{id}",liquidationId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.finalAmount").value(100.0));
        Debt debt=debts.findByTaxpayerId(taxpayer.id).get(0);

        long adjustmentId=id(postJson("/api/v1/adjustments",
            "{\"debtId\":"+debt.id+",\"type\":\"DISCOUNT\",\"amount\":0.01,\"reason\":\"Redondeo autorizado\"}",201,rentas));
        postJson("/api/v1/adjustments/"+adjustmentId+"/approve",null,200);
        mvc.perform(withStaff(get("/api/v1/adjustments/{id}",adjustmentId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.newDebtAmount").value(99.99));

        long billId=id(postJson("/api/v1/bills","{\"taxpayerId\":"+taxpayer.id+",\"debtIds\":["+debt.id+
            "],\"dueDate\":\""+due+"\"}",201));
        mvc.perform(withStaff(get("/api/v1/bills/{id}",billId))).andExpect(status().isOk()).andExpect(jsonPath("$.debts.length()").value(1));
        mvc.perform(withStaff(get("/api/v1/bills/{id}/document",billId))).andExpect(status().isOk())
            .andExpect(result->assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE));

        long paymentId=id(postJson("/api/v1/payments","{\"taxpayerId\":"+taxpayer.id+",\"billId\":"+billId+
            ",\"paymentMethod\":\"CASH\",\"amount\":99.99,\"allocations\":[{\"debtId\":"+debt.id+",\"amount\":99.99}]}",201));
        mvc.perform(withStaff(get("/api/v1/payments/{id}",paymentId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value(99.99)).andExpect(jsonPath("$.unallocatedAmount").value(0));
        mvc.perform(withStaff(get("/api/v1/payments/{id}/receipt",paymentId))).andExpect(status().isOk()).andExpect(jsonPath("$.receiptNumber").isNotEmpty());
        mvc.perform(withStaff(get("/api/v1/payments/{id}/allocations",paymentId))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].principalApplied").value(99.99)).andExpect(jsonPath("$[0].interestApplied").value(0));

        long reversalId=id(postJson("/api/v1/payments/"+paymentId+"/reversal-requests","{\"reason\":\"Duplicado\"}",201,demo.token(DemoRole.CASHIER)));
        postJson("/api/v1/payment-reversals/"+reversalId+"/approve",null,200);
        postJson("/api/v1/payment-reversals/"+reversalId+"/execute",null,200);
        mvc.perform(withStaff(get("/api/v1/payment-reversals/{id}",reversalId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("EXECUTED"));
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("99.99");

        String nextPeriod=YearMonth.now().plusMonths(1).toString();
        long runId=id(postJson("/api/v1/liquidation-runs","{\"taxConceptId\":"+conceptId+",\"period\":\""+nextPeriod+
            "\",\"dueDate\":\""+due+"\",\"items\":[{\"taxpayerId\":"+taxpayer.id+",\"taxableBase\":0}]}",201,rentas));
        postJson("/api/v1/liquidation-runs/"+runId+"/preview",null,200);
        postJson("/api/v1/liquidation-runs/"+runId+"/submit",null,200,rentas);
        postJson("/api/v1/liquidation-runs/"+runId+"/approve",null,200);
        postJson("/api/v1/liquidation-runs/"+runId+"/execute",null,200);
        mvc.perform(withStaff(get("/api/v1/liquidation-runs/{id}",runId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.run.status").value("EXECUTED")).andExpect(jsonPath("$.items.length()").value(1));

        Debt planDebt=debts.findByTaxpayerId(taxpayer.id).stream().filter(x->!x.id.equals(debt.id)).findFirst().orElseThrow();
        JsonNode planConfigurationResponse=postJson("/api/v1/payment-plan-configurations",
            "{\"minimumInstallments\":2,\"maximumInstallments\":6,\"minimumDownPaymentPercentage\":10,"+
                "\"interestRate\":12,\"graceDays\":0,\"maxOverdueInstallments\":0,"+
                "\"partialInstallmentPaymentAllowed\":true,\"refinancingAllowed\":true,\"maxRefinancingCount\":2,"+
                "\"validFrom\":\""+validFrom+"\",\"active\":true}",201);
        long planConfigurationId=planConfigurationResponse.path("id").asLong();
        mvc.perform(withStaff(get("/api/v1/payment-plan-configurations/{id}",planConfigurationId))).andExpect(status().isOk());
        String planBody="{\"taxpayerId\":"+taxpayer.id+",\"debtIds\":["+planDebt.id+"],\"installments\":2}";
        postJson("/api/v1/payment-plans/simulations",planBody,200);
        String requestResponse=mvc.perform(post("/api/v1/payment-plan-requests")
                .header("X-Demo-Session",demo.token(DemoRole.TAXPAYER,taxpayer.id))
                .contentType(MediaType.APPLICATION_JSON).content(planBody))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long planRequestId=id(json.readTree(requestResponse));
        JsonNode granted=postJson("/api/v1/payment-plan-requests/"+planRequestId+"/grant",null,200);
        long planId=granted.path("paymentPlanId").asLong();
        mvc.perform(withStaff(get("/api/v1/payment-plan-requests/{id}",planRequestId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("GRANTED"));
        mvc.perform(withStaff(get("/api/v1/payment-plans/{id}",planId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.configurationVersion").value(planConfigurationResponse.path("version").asInt()));
        mvc.perform(withStaff(get("/api/v1/payment-plans/{id}/installments",planId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(withStaff(get("/api/v1/taxpayers/{id}/payment-plans",taxpayer.id))).andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1));

        mvc.perform(withStaff(get("/api/v1/taxpayers/{id}/summary",taxpayer.id))).andExpect(status().isOk());
        mvc.perform(withStaff(get("/api/v1/taxpayers/{id}/debts",taxpayer.id))).andExpect(status().isOk());
        mvc.perform(withStaff(get("/api/v1/taxpayers/{id}/debts/summary",taxpayer.id))).andExpect(status().isOk());
        mvc.perform(withStaff(get("/api/v1/taxpayers/{id}/bills",taxpayer.id))).andExpect(status().isOk());
        mvc.perform(withStaff(get("/api/v1/taxpayers/{id}/payments",taxpayer.id))).andExpect(status().isOk());
        mvc.perform(withStaff(get("/api/v1/indicators/summary"))).andExpect(status().isOk());
        mvc.perform(withStaff(get("/api/v1/indicators/collection"))).andExpect(status().isOk());
        mvc.perform(withStaff(get("/api/v1/indicators/debt"))).andExpect(status().isOk());
        mvc.perform(withStaff(get("/api/v1/indicators/delinquency"))).andExpect(status().isOk());
        mvc.perform(withStaff(get("/api/v1/audit/entities/Payment/"+paymentId))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].correlationId").isNotEmpty());
        mvc.perform(withStaff(get("/api/v1/integrations/outbox"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isNotEmpty());
    }

    @Test void socialBenefitRuleApiUsesDtosAndValidatesPercentageBeforePersistence() throws Exception {
        String url="/api/v1/social-benefit-rules";
        TaxConcept concept=new TaxConcept();concept.code="SBR_DTO_TEST";concept.name=concept.code;concept.type=TaxConceptType.FEE;
        concept.originModule="M5";concept.active=true;concept.createdAt=concept.updatedAt=OffsetDateTime.now();concepts.save(concept);
        String invalid="{\"programId\":\"28d8ec18-9639-4d86-858a-b48c590ee702\",\"conceptCode\":\"SBR_DTO_TEST\",\"percentage\":150,\"validFrom\":\"2026-10-28\"}";
        var error=mvc.perform(post(url).header("X-Demo-Session",rentas).contentType(MediaType.APPLICATION_JSON).content(invalid))
            .andExpect(status().isBadRequest()).andReturn();
        JsonNode errorBody=json.readTree(error.getResponse().getContentAsString());
        assertThat(errorBody.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(errorBody.path("details").path("fieldErrors").toString()).contains("percentage");

        String valid="{\"programId\":\"28d8ec18-9639-4d86-858a-b48c590ee702\",\"conceptCode\":\"SBR_DTO_TEST\",\"percentage\":10,\"validFrom\":\"2026-10-28\"}";
        JsonNode created=postJson(url,valid,201,rentas);
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("programId").asText()).isEqualTo("28d8ec18-9639-4d86-858a-b48c590ee702");
        assertThat(created.has("approvedActorId")).isFalse();

        String ruleId=created.path("id").asText();
        JsonNode submitted=postJson(url+"/"+ruleId+"/submit","{}",200,rentas);
        assertThat(submitted.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        JsonNode approved=postJson(url+"/"+ruleId+"/approve","{}",200,staff);
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("enabled").asBoolean()).isTrue();
        JsonNode disabled=postJson(url+"/"+ruleId+"/disable","{}",200,staff);
        assertThat(disabled.path("enabled").asBoolean()).isFalse();
        assertThat(disabled.has("approvedActorId")).isFalse();

        var listed=mvc.perform(get(url).header("X-Demo-Session",staff)).andExpect(status().isOk()).andReturn();
        JsonNode list=json.readTree(listed.getResponse().getContentAsString());
        JsonNode listedRule=java.util.stream.StreamSupport.stream(list.spliterator(),false)
            .filter(item->item.path("id").asLong()==created.path("id").asLong()).findFirst().orElseThrow();
        assertThat(listedRule.has("approvedActorId")).isFalse();
        assertThat(listedRule.path("percentage").decimalValue()).isEqualByComparingTo("10.00");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withStaff(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-Demo-Session",staff);
    }
    private JsonNode postJson(String url,String body,int expected) throws Exception { return postJson(url,body,expected,staff); }
    private JsonNode postJson(String url,String body,int expected,String session) throws Exception {
        var request=post(url).header("X-Demo-Session",session);if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(body);
        String response=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank()?json.createObjectNode():json.readTree(response);
    }
    private void patchJson(String url,String body) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url)
            .header("X-Demo-Session",staff)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
    private long id(JsonNode node){return node.path("id").asLong();}
}
