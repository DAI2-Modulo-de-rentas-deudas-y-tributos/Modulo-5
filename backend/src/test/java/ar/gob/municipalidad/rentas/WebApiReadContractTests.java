package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WebApiReadContractTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired DebtRepository debts;

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
            mvc.perform(get(url).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page.size").value(2));
        }
    }

    @Test void missingResourcesUseTheSameSafeErrorContract() throws Exception {
        String[] missing = {
            "/api/v1/taxpayers/999999", "/api/v1/tax-concepts/999999",
            "/api/v1/tax-configurations/999999", "/api/v1/liquidations/999999",
            "/api/v1/liquidation-runs/999999", "/api/v1/adjustments/999999",
            "/api/v1/debts/999999", "/api/v1/bills/999999", "/api/v1/payments/999999",
            "/api/v1/credit-balances/999999", "/api/v1/payment-reversals/999999",
            "/api/v1/payment-plan-configurations/999999", "/api/v1/payment-plan-requests/999999",
            "/api/v1/payment-plans/999999", "/api/v1/payment-plan-expirations/999999",
            "/api/v1/refinancing-requests/999999", "/api/v1/exemption-requests/999999",
            "/api/v1/exemptions/999999", "/api/v1/tickets/999999",
            "/api/v1/social-benefits/999999", "/api/v1/external-obligations/999999",
            "/api/v1/audit/999999",
            "/api/v1/integrations/events/00000000-0000-0000-0000-000000000001"
        };
        for (String url : missing) {
            var result = mvc.perform(get(url))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception", "Hibernate", "SQL");
        }
    }

    @Test void malformedAndInvalidRequestsReturnStableClientErrors() throws Exception {
        mvc.perform(post("/api/v1/tax-concepts").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/tax-concepts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"\",\"name\":\"\",\"type\":\"FEE\",\"originModule\":\"M5\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/tax-concepts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"X\",\"name\":\"X\",\"type\":\"NOT_A_TYPE\",\"originModule\":\"M5\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
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
            "/api/v1/tickets/999999/complete", "/api/v1/tickets/999999/reject",
            "/api/v1/payments/999999/reversal-requests"
        };
        for (String url : withReason) assertMissing(post(url).contentType(MediaType.APPLICATION_JSON)
            .content(url.endsWith("/complete") ? "{\"resolution\":\"motivo válido\"}" :
                url.contains("request-documentation") || url.endsWith("/updates") || url.endsWith("request-information")
                    ? "{\"message\":\"motivo válido\"}" : "{\"reason\":\"motivo válido\"}"));

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
        mvc.perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void coreRestWorkflowPreservesEconomicInvariantsAndResponseContracts() throws Exception {
        TaxpayerReference taxpayer=new TaxpayerReference();taxpayer.taxpayerType=TaxpayerType.CITIZEN;
        taxpayer.externalId="API-HARDENING";taxpayer.displayName="Contrato API";taxpayer.externalStatus=TaxpayerStatus.ACTIVE;
        taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();taxpayers.save(taxpayer);

        long conceptId=id(postJson("/api/v1/tax-concepts",
            "{\"code\":\"API-HARD\",\"name\":\"Tasa API\",\"type\":\"FEE\",\"originModule\":\"M5\"}",201));
        patchJson("/api/v1/tax-concepts/"+conceptId,
            "{\"name\":\"Tasa API endurecida\",\"description\":\"contrato\",\"active\":true}");
        mvc.perform(get("/api/v1/tax-concepts/{id}",conceptId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Tasa API endurecida"));

        String validFrom=LocalDate.now().minusDays(1).toString();
        long configurationId=id(postJson("/api/v1/tax-configurations",
            "{\"taxConceptId\":"+conceptId+",\"calculationType\":\"FIXED\",\"fixedAmount\":100,"+
                "\"partialPaymentAllowed\":true,\"paymentPlanAllowed\":true,\"validFrom\":\""+validFrom+"\"}",201));
        patchJson("/api/v1/tax-configurations/"+configurationId,"{\"fixedAmount\":100.00}");
        postJson("/api/v1/tax-configurations/"+configurationId+"/submit",null,200);
        postJson("/api/v1/tax-configurations/"+configurationId+"/approve",null,200);
        mvc.perform(get("/api/v1/tax-configurations/{id}",configurationId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        String period=YearMonth.now().toString();String due=LocalDate.now().plusDays(30).toString();
        String liquidation="{\"taxpayerId\":"+taxpayer.id+",\"taxConceptId\":"+conceptId+",\"period\":\""+period+
            "\",\"taxableBase\":0,\"dueDate\":\""+due+"\"}";
        postJson("/api/v1/liquidations/preview",liquidation,200);
        long liquidationId=id(postJson("/api/v1/liquidations",liquidation,201));
        mvc.perform(get("/api/v1/liquidations/{id}",liquidationId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.finalAmount").value(100.0));
        Debt debt=debts.findByTaxpayerId(taxpayer.id).get(0);

        long adjustmentId=id(postJson("/api/v1/adjustments",
            "{\"debtId\":"+debt.id+",\"type\":\"DISCOUNT\",\"amount\":0.01,\"reason\":\"Redondeo autorizado\"}",201));
        postJson("/api/v1/adjustments/"+adjustmentId+"/approve",null,200);
        mvc.perform(get("/api/v1/adjustments/{id}",adjustmentId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.newDebtAmount").value(99.99));

        long billId=id(postJson("/api/v1/bills","{\"taxpayerId\":"+taxpayer.id+",\"debtIds\":["+debt.id+
            "],\"dueDate\":\""+due+"\"}",201));
        mvc.perform(get("/api/v1/bills/{id}",billId)).andExpect(status().isOk()).andExpect(jsonPath("$.debts.length()").value(1));
        mvc.perform(get("/api/v1/bills/{id}/document",billId)).andExpect(status().isOk())
            .andExpect(result->assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE));

        long paymentId=id(postJson("/api/v1/payments","{\"taxpayerId\":"+taxpayer.id+",\"billId\":"+billId+
            ",\"paymentMethod\":\"CASH\",\"amount\":99.99,\"allocations\":[{\"debtId\":"+debt.id+",\"amount\":99.99}]}",201));
        mvc.perform(get("/api/v1/payments/{id}",paymentId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value(99.99)).andExpect(jsonPath("$.unallocatedAmount").value(0));
        mvc.perform(get("/api/v1/payments/{id}/receipt",paymentId)).andExpect(status().isOk()).andExpect(jsonPath("$.receiptNumber").isNotEmpty());
        mvc.perform(get("/api/v1/payments/{id}/allocations",paymentId)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].principalApplied").value(99.99)).andExpect(jsonPath("$[0].interestApplied").value(0));

        long reversalId=id(postJson("/api/v1/payments/"+paymentId+"/reversal-requests","{\"reason\":\"Duplicado\"}",201));
        postJson("/api/v1/payment-reversals/"+reversalId+"/approve",null,200);
        postJson("/api/v1/payment-reversals/"+reversalId+"/execute",null,200);
        mvc.perform(get("/api/v1/payment-reversals/{id}",reversalId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("EXECUTED"));
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("99.99");

        String nextPeriod=YearMonth.now().plusMonths(1).toString();
        long runId=id(postJson("/api/v1/liquidation-runs","{\"taxConceptId\":"+conceptId+",\"period\":\""+nextPeriod+
            "\",\"dueDate\":\""+due+"\",\"items\":[{\"taxpayerId\":"+taxpayer.id+",\"taxableBase\":0}]}",201));
        postJson("/api/v1/liquidation-runs/"+runId+"/preview",null,200);
        postJson("/api/v1/liquidation-runs/"+runId+"/submit",null,200);
        postJson("/api/v1/liquidation-runs/"+runId+"/approve",null,200);
        postJson("/api/v1/liquidation-runs/"+runId+"/execute",null,200);
        mvc.perform(get("/api/v1/liquidation-runs/{id}",runId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.run.status").value("EXECUTED")).andExpect(jsonPath("$.items.length()").value(1));

        Debt planDebt=debts.findByTaxpayerId(taxpayer.id).stream().filter(x->!x.id.equals(debt.id)).findFirst().orElseThrow();
        long planConfigurationId=id(postJson("/api/v1/payment-plan-configurations",
            "{\"minimumInstallments\":2,\"maximumInstallments\":6,\"minimumDownPaymentPercentage\":10,"+
                "\"interestRate\":12,\"graceDays\":0,\"maxOverdueInstallments\":0,"+
                "\"partialInstallmentPaymentAllowed\":true,\"refinancingAllowed\":true,\"maxRefinancingCount\":2,"+
                "\"validFrom\":\""+validFrom+"\",\"active\":true}",201));
        mvc.perform(get("/api/v1/payment-plan-configurations/{id}",planConfigurationId)).andExpect(status().isOk());
        String planBody="{\"taxpayerId\":"+taxpayer.id+",\"debtIds\":["+planDebt.id+"],\"installments\":2}";
        postJson("/api/v1/payment-plans/simulations",planBody,200);
        String requestResponse=mvc.perform(post("/api/v1/payment-plan-requests")
                .header("X-Dev-Roles","TAXPAYER").header("X-Dev-Taxpayer-Id",taxpayer.id)
                .contentType(MediaType.APPLICATION_JSON).content(planBody))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long planRequestId=id(json.readTree(requestResponse));
        JsonNode granted=postJson("/api/v1/payment-plan-requests/"+planRequestId+"/grant",null,200);
        long planId=granted.path("paymentPlanId").asLong();
        mvc.perform(get("/api/v1/payment-plan-requests/{id}",planRequestId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("GRANTED"));
        mvc.perform(get("/api/v1/payment-plans/{id}",planId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.configurationVersion").value(1));
        mvc.perform(get("/api/v1/payment-plans/{id}/installments",planId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/v1/taxpayers/{id}/payment-plans",taxpayer.id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1));

        mvc.perform(get("/api/v1/taxpayers/{id}/summary",taxpayer.id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxpayers/{id}/debts",taxpayer.id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxpayers/{id}/debts/summary",taxpayer.id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxpayers/{id}/bills",taxpayer.id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxpayers/{id}/payments",taxpayer.id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/indicators/summary")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/indicators/collection")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/indicators/debt")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/indicators/delinquency")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/audit/entities/Payment/"+paymentId)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].correlationId").isNotEmpty());
        mvc.perform(get("/api/v1/integrations/outbox")).andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isNotEmpty());
    }

    private JsonNode postJson(String url,String body,int expected) throws Exception {
        var request=post(url);if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(body);
        String response=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank()?json.createObjectNode():json.readTree(response);
    }
    private void patchJson(String url,String body) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
    private long id(JsonNode node){return node.path("id").asLong();}
}
