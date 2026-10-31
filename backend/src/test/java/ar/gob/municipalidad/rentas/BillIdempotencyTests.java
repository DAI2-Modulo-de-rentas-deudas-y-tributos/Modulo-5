package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BillIdempotencyTests {
    @Autowired MockMvc mvc;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired DebtRepository debts;
    @Autowired BillRepository bills;
    @Autowired BillDebtRepository billDebts;
    @Autowired DemoUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;
    private DemoAuthSessions demo;

    @BeforeEach void setup() {
        demo=new DemoAuthSessions(users,taxpayers,encoder,demoAuth);
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("bill-idempotency-test",null),null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_RENTAS"))));
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void retryReturnsTheBillAndKeyReuseWithDifferentRequestConflicts() throws Exception {
        Debt first=debt("BILL-IDEM-A"),second=debtFor(first.taxpayerId,"BILL-IDEM-B");
        String session=demo.token(DemoRole.RENTAS),key="bill-idem-"+UUID.randomUUID();
        String body="{\"taxpayerId\":"+first.taxpayerId+",\"debtIds\":["+first.id+"],\"dueDate\":\""+LocalDate.now().plusDays(10)+"\"}";
        SecurityContextHolder.clearContext();
        var created=mvc.perform(post("/api/v1/bills").header("X-Demo-Session",session).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        long billId=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).path("id").asLong();

        mvc.perform(post("/api/v1/bills").header("X-Demo-Session",session).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(billId));
        mvc.perform(post("/api/v1/bills").header("X-Demo-Session",session).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("["+first.id+"]","["+second.id+"]")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(bills.findByTaxpayerId(first.taxpayerId)).hasSize(1);
        assertThat(billDebts.findByBillId(billId)).singleElement().satisfies(link->assertThat(link.debtId).isEqualTo(first.id));
    }

    private Debt debt(String suffix) {
        TaxpayerReference taxpayer=new TaxpayerReference();taxpayer.taxpayerType=TaxpayerType.CITIZEN;taxpayer.externalId=suffix+"-"+UUID.randomUUID();taxpayer.dni=Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());taxpayer.displayName=suffix;taxpayer.externalStatus=TaxpayerStatus.ACTIVE;taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();taxpayers.save(taxpayer);
        return debtFor(taxpayer.id,suffix);
    }

    private Debt debtFor(Long taxpayerId,String suffix) {
        TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest((suffix+UUID.randomUUID()).replace("-","_"),suffix,null,TaxConceptType.FEE,"M5"));
        TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));
        catalog.submit(configuration.id);TestActorSupport.asApprover(() -> catalog.approve(configuration.id));
        liquidations.create(new ApiDtos.LiquidationRequest(taxpayerId,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));
        return debts.findByTaxpayerId(taxpayerId).stream().filter(debt->debt.taxConceptId.equals(concept.id)).findFirst().orElseThrow();
    }
}
