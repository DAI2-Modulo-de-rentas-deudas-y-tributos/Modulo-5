package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@ActiveProfiles("test") @SpringBootTest @AutoConfigureMockMvc @Transactional
class SecurityTests {
    @Autowired MockMvc mvc; @Autowired TaxpayerRepository taxpayers; @Autowired BillRepository bills; @Autowired ObjectMapper json;

    @Test void auditorCannotWrite() throws Exception {
        mvc.perform(post("/api/v1/tax-concepts").header("X-Dev-Roles","AUDITOR").contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"X\",\"name\":\"X\",\"type\":\"FEE\",\"originModule\":\"M5\"}"))
            .andExpect(status().isForbidden());
    }

    @Test void rentasCanReachPaymentRegistrationFromItsOperationalScreen() throws Exception {
        mvc.perform(post("/api/v1/payments").header("X-Dev-Roles","RENTAS").contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test void taxpayerCanReadTheConceptCatalogRequiredByExemptions() throws Exception {
        mvc.perform(get("/api/v1/tax-concepts").header("X-Dev-Roles","TAXPAYER"))
            .andExpect(status().isOk());
    }

    @Test void taxpayerCannotReadAnotherTaxpayer() throws Exception {
        TaxpayerReference t=new TaxpayerReference();t.taxpayerType=TaxpayerType.CITIZEN;t.externalId="SEC-1";t.displayName="Uno";t.externalStatus=TaxpayerStatus.ACTIVE;t.createdAt=t.updatedAt=OffsetDateTime.now();taxpayers.save(t);
        mvc.perform(get("/api/v1/taxpayers/{id}/summary",t.id).header("X-Dev-Roles","TAXPAYER").header("X-Dev-Taxpayer-Id",t.id+1))
            .andExpect(status().isForbidden());
    }

    @Test void taxpayerCannotReadAnotherTaxpayersBill() throws Exception {
        TaxpayerReference owner=new TaxpayerReference();owner.taxpayerType=TaxpayerType.CITIZEN;owner.externalId="SEC-BILL";owner.displayName="Titular";owner.externalStatus=TaxpayerStatus.ACTIVE;owner.createdAt=owner.updatedAt=OffsetDateTime.now();taxpayers.save(owner);
        Bill bill=new Bill();bill.number="SEC-BILL-1";bill.taxpayerId=owner.id;bill.totalAmount=java.math.BigDecimal.TEN;bill.issueDate=java.time.LocalDate.now();bill.dueDate=java.time.LocalDate.now().plusDays(1);bill.status=BillStatus.ISSUED;bill.createdBy="test";bill.createdAt=OffsetDateTime.now();bills.save(bill);
        mvc.perform(get("/api/v1/bills/{id}",bill.id).header("X-Dev-Roles","TAXPAYER").header("X-Dev-Taxpayer-Id",owner.id+1))
            .andExpect(status().isForbidden());
    }

    @Test void healthAndOpenApiArePublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        String openApi=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var paths=json.readTree(openApi).path("paths");int operations=0;for(var path=paths.fields();path.hasNext();)operations+=path.next().getValue().size();
        org.assertj.core.api.Assertions.assertThat(operations).isEqualTo(147);
        org.assertj.core.api.Assertions.assertThat(paths.size()).isEqualTo(132);
        org.assertj.core.api.Assertions.assertThat(paths.fieldNames()).toIterable().noneMatch(x->x.startsWith("/events/"));
    }
}
