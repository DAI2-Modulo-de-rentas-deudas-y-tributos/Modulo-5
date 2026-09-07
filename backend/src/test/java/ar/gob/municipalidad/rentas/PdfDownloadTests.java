package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SCRUM-139: contrato HTTP, aislamiento y evidencia que luego lee un parser PDF independiente. */
@ActiveProfiles("test")
@SpringBootTest(properties = "rentas.security.dev-mode=false")
@AutoConfigureMockMvc
@Transactional
class PdfDownloadTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired BillingService billing;
    @Autowired PaymentService payments;
    @Autowired BillRepository bills;
    @Autowired BillDebtRepository billDebts;
    @Autowired DebtRepository debts;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RENTAS", "CASHIER", "TAXPAYER"})
    void authorizedRolesCanDownload(String role) throws Exception {
        Bill bill = issue(1);
        mvc.perform(download(bill, role, bill.taxpayerId))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + bill.number + ".pdf\""))
            .andExpect(header().exists("X-Correlation-Id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUDITOR", "SUPERVISOR", "TECHNICAL"})
    void rolesWithoutDownloadPermissionDoNotReceivePdf(String role) throws Exception {
        Bill bill = issue(1);
        assertDenied(download(bill, role, null));
    }

    @Test
    void taxpayerCannotDownloadAnotherOwnersBillOrOmitIdentity() throws Exception {
        Bill bill = issue(1);
        assertDenied(download(bill, "TAXPAYER", bill.taxpayerId + 1));
        assertDenied(download(bill, "TAXPAYER", null));
    }

    @Test
    void anonymousAndForgedDevHeadersCannotDownloadWhenDevModeIsDisabled() throws Exception {
        Bill bill = issue(1);
        assertDenied(get("/api/v1/bills/{id}/document", bill.id));
        assertDenied(get("/api/v1/bills/{id}/document", bill.id)
            .header("X-Dev-User", "forged").header("X-Dev-Roles", "RENTAS"));
    }

    @Test
    void unknownBillReturnsNotFoundInsteadOfPdf() throws Exception {
        var response = mvc.perform(get("/api/v1/bills/{id}/document", Long.MAX_VALUE)
                .with(authentication(identity("RENTAS", null))))
            .andExpect(status().isNotFound()).andReturn().getResponse();
        assertThat(response.getContentType()).doesNotContain("application/pdf");
        assertThat(response.getContentAsString()).doesNotStartWith("%PDF-");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 45})
    void emitsReadableDocumentForSingleMultipleAndPaginatedDebts(int count) throws Exception {
        Bill bill = issue(count);
        byte[] document = mvc.perform(download(bill, "RENTAS", null))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        // CI parsea todos los archivos con pypdf y los renderiza con Poppler.
        Path directory = Path.of("target", "pdf-evidence");
        Files.createDirectories(directory);
        String name = "bill-" + count;
        Files.write(directory.resolve(name + ".pdf"), document);
        json.writeValue(directory.resolve(name + ".json").toFile(), Map.of(
            "bill", ApiResponses.of(bill, billDebts.findByBillId(bill.id)),
            "debts", billDebts.findByBillId(bill.id),
            "expectedPages", count == 45 ? 2 : 1
        ));
        assertThat(document).startsWith("%PDF-".getBytes());
        assertThat(bill.totalAmount).isEqualByComparingTo(new BigDecimal("100.25").multiply(BigDecimal.valueOf(count)));
        assertThat(debts.findByTaxpayerId(bill.taxpayerId))
            .allSatisfy(debt -> assertThat(debt.outstandingBalance).isEqualByComparingTo("100.25"));
    }

    @Test
    void repeatedDownloadsPreserveIssuedAmountsAfterPaymentAndDoNotWrite() throws Exception {
        Bill bill = issue(3);
        byte[] original = mvc.perform(download(bill, "RENTAS", null)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        Debt paid = debts.findByTaxpayerId(bill.taxpayerId).get(0);
        SecurityContextHolder.getContext().setAuthentication(identity("CASHIER", null));
        payments.register(new ApiDtos.RegisterPaymentRequest(bill.taxpayerId, PaymentMethod.CASH,
            paid.outstandingBalance, List.of(new ApiDtos.AllocationRequest(paid.id, paid.outstandingBalance))));
        SecurityContextHolder.clearContext();
        long billCount = bills.count();
        long linkCount = billDebts.count();
        long debtCount = debts.count();
        for (int i = 0; i < 3; i++) {
            mvc.perform(download(bill, "TAXPAYER", bill.taxpayerId))
                .andExpect(status().isOk()).andExpect(content().bytes(original));
        }
        assertThat(bills.count()).isEqualTo(billCount);
        assertThat(billDebts.count()).isEqualTo(linkCount);
        assertThat(debts.count()).isEqualTo(debtCount);
        assertThat(debts.findById(paid.id).orElseThrow().outstandingBalance).isZero();
        assertThat(bills.findById(bill.id).orElseThrow().totalAmount).isEqualByComparingTo("300.75");
    }

    private void assertDenied(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andExpect(status().isForbidden()).andReturn().getResponse();
        assertThat(response.getContentType()).isNotEqualTo("application/pdf");
        assertThat(response.getContentAsString()).doesNotStartWith("%PDF-");
    }

    private MockHttpServletRequestBuilder download(Bill bill, String role, Long taxpayerId) {
        return get("/api/v1/bills/{id}/document", bill.id).with(authentication(identity(role, taxpayerId)));
    }

    private UsernamePasswordAuthenticationToken identity(String role, Long taxpayerId) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("qa-pdf", taxpayerId), null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private Bill issue(int count) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(identity("RENTAS", null));
        TaxpayerReference taxpayer = catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.CITIZEN, "PDF-QA", "12345678", null, "Persona QA"));
        TaxConcept concept = catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
            "PDF-QA", "Concepto QA", null, TaxConceptType.FEE, "M5"));
        TaxConfiguration configuration = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            concept.id, CalculationType.FIXED, null, new BigDecimal("100.25"), null, null,
            true, true, LocalDate.now().minusDays(1), null));
        catalog.submit(configuration.id);
        catalog.approve(configuration.id);
        for (int index = 0; index < count; index++) {
            liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id, concept.id,
                YearMonth.now().plusMonths(index).toString(), BigDecimal.ZERO, LocalDate.now().plusDays(30)));
        }
        List<Long> ids = debts.findByTaxpayerId(taxpayer.id).stream().map(debt -> debt.id).toList();
        SecurityContextHolder.clearContext();
        String response = mvc.perform(post("/api/v1/bills").with(authentication(identity("RENTAS", null)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(
                    new ApiDtos.CreateBillRequest(taxpayer.id, ids, LocalDate.now().plusDays(10)))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return bills.findById(json.readTree(response).path("id").asLong()).orElseThrow();
    }
}
