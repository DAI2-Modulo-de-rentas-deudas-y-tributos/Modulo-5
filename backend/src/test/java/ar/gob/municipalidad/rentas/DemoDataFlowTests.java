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
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test") @SpringBootTest @AutoConfigureMockMvc @Transactional
@org.springframework.test.context.jdbc.Sql(statements="INSERT INTO demo_bootstrap_lock(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM demo_bootstrap_lock WHERE id=1)")
class DemoDataFlowTests {
    private static final String PASSWORD="clave-demo-tests";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DemoUserRepository users;
    @Autowired DemoAuthSessionRepository sessions;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired DebtRepository debts;
    @Autowired PaymentPlanConfigurationRepository planConfigurations;

    @BeforeEach void resetAuth(){sessions.deleteAll();users.deleteAll();users.flush();}

    @Test void inicializaUsuariosContribuyenteDeudasYConfiguracionDePlanes() throws Exception {
        JsonNode result=initialize(supervisor());
        Long taxpayerId=result.path("taxpayerId").longValue();

        assertThat(result.path("debtA").longValue()).isPositive();
        assertThat(result.path("debtB").longValue()).isPositive().isNotEqualTo(result.path("debtA").longValue());
        assertThat(users.findByUsernameIgnoreCase("rentas.demo")).get().extracting(x->x.role).isEqualTo(DemoRole.RENTAS);
        assertThat(users.findByUsernameIgnoreCase("cajero.demo")).get().extracting(x->x.role).isEqualTo(DemoRole.CASHIER);
        assertThat(users.findByUsernameIgnoreCase("contribuyente.demo")).get().satisfies(x->{
            assertThat(x.role).isEqualTo(DemoRole.TAXPAYER);assertThat(x.taxpayerId).isEqualTo(taxpayerId);
        });
        assertThat(debts.findByTaxpayerId(taxpayerId)).filteredOn(x->x.status==DebtStatus.PENDING).hasSize(2);
        assertThat(planConfigurations.findById(result.path("activePlanConfigurationId").longValue())).get().satisfies(x->{
            assertThat(x.active).isTrue();assertThat(x.minimumInstallments).isLessThanOrEqualTo(3);assertThat(x.maximumInstallments).isGreaterThanOrEqualTo(12);
        });
        login("contribuyente.demo",PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.user.taxpayerId").value(taxpayerId));
    }

    @Test void inicializacionEsIdempotente() throws Exception {
        String supervisor=supervisor();JsonNode first=initialize(supervisor);JsonNode second=initialize(supervisor);
        assertThat(second.path("taxpayerId").longValue()).isEqualTo(first.path("taxpayerId").longValue());
        assertThat(second.path("debtA").longValue()).isEqualTo(first.path("debtA").longValue());
        assertThat(second.path("debtB").longValue()).isEqualTo(first.path("debtB").longValue());
        assertThat(taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,"DEMO-TAXPAYER-001")).isPresent();
        assertThat(users.findAll()).hasSize(4);
    }

    @Test void personalRentasNoPuedeInicializarDatosDemo() throws Exception {
        String supervisor=supervisor();
        mvc.perform(post("/api/v1/dev-auth/users").header("X-Demo-Session",supervisor).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new DemoAuthController.CreateUserRequest("rentas.demo",PASSWORD,"Rentas",DemoRole.RENTAS,null))))
            .andExpect(status().isCreated());
        String token=json.readTree(login("rentas.demo",PASSWORD).andReturn().getResponse().getContentAsString()).path("token").asText();
        mvc.perform(post("/api/v1/dev-auth/demo-data").header("X-Demo-Session",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("password",PASSWORD)))).andExpect(status().isForbidden());
    }

    @Test void flujoDemoCompletoActualizaCuotaYSaldoDelPlan() throws Exception {
        JsonNode demo=initialize(supervisor());Long taxpayerId=demo.path("taxpayerId").longValue();
        Long debtA=demo.path("debtA").longValue();Long debtB=demo.path("debtB").longValue();
        String taxpayerToken=token("contribuyente.demo");

        JsonNode debt=json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/debts/{id}",debtA)
            .header("X-Demo-Session",taxpayerToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/electronic-payments").header("X-Demo-Session",taxpayerToken).header("Idempotency-Key","demo-electronic-test")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("debtId",debtA,"paymentMethod","CARD","amount",debt.path("outstandingBalance").decimalValue()))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPROVED"));

        JsonNode request=json.readTree(mvc.perform(post("/api/v1/payment-plan-requests").header("X-Demo-Session",taxpayerToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("taxpayerId",taxpayerId,"debtIds",List.of(debtB),"installments",3))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String rentasToken=token("rentas.demo");
        JsonNode granted=json.readTree(mvc.perform(post("/api/v1/payment-plan-requests/{id}/grant",request.path("id").longValue())
            .header("X-Demo-Session",rentasToken).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("GRANTED"))
            .andReturn().getResponse().getContentAsString());
        Long planId=granted.path("paymentPlanId").longValue();
        JsonNode planBefore=json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/payment-plans/{id}",planId)
            .header("X-Demo-Session",taxpayerToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode installments=json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/payment-plans/{id}/installments",planId)
            .header("X-Demo-Session",taxpayerToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode first=installments.get(0);String cashierToken=token("cajero.demo");

        mvc.perform(post("/api/v1/payments").header("X-Demo-Session",cashierToken).header("Idempotency-Key","demo-installment-test")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("taxpayerId",taxpayerId,"paymentMethod","CASH",
                "amount",first.path("outstandingAmount").decimalValue(),"allocations",List.of(Map.of("installmentId",first.path("id").longValue(),"amount",first.path("outstandingAmount").decimalValue()))))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.allocationStatus").value("FULLY_ALLOCATED"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/payment-plans/{planId}/installments/{installmentId}",planId,first.path("id").longValue())
            .header("X-Demo-Session",taxpayerToken)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.outstandingAmount").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/payment-plans/{id}",planId).header("X-Demo-Session",taxpayerToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outstandingPlanAmount").value(org.hamcrest.Matchers.lessThan(planBefore.path("outstandingPlanAmount").doubleValue())));
    }

    private JsonNode initialize(String token) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/dev-auth/demo-data").header("X-Demo-Session",token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("password",PASSWORD))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.users.length()").value(4))
            .andReturn().getResponse().getContentAsString());
    }
    private String supervisor() throws Exception {
        mvc.perform(post("/api/v1/dev-auth/bootstrap").header("X-Demo-Bootstrap-Secret","test-bootstrap-secret")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username","supervisor.demo","password",PASSWORD,"displayName","Supervisor Demo"))))
            .andExpect(status().isCreated());
        return json.readTree(login("supervisor.demo",PASSWORD).andReturn().getResponse().getContentAsString()).path("token").asText();
    }
    private String token(String username) throws Exception {return json.readTree(login(username,PASSWORD).andReturn().getResponse().getContentAsString()).path("token").asText();}
    private org.springframework.test.web.servlet.ResultActions login(String username,String password) throws Exception {
        return mvc.perform(post("/api/v1/dev-auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username",username,"password",password)))).andExpect(status().isOk());
    }
}
