package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OperationalAcceptanceTests {
    @Autowired MockMvc mvc;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired BillingService billing;
    @Autowired LiquidationRunService runs;
    @Autowired DebtRepository debts;
    @Autowired LiquidationRepository liquidationRepository;

    @BeforeEach
    void authenticateEmployee() {
        authenticate("RENTAS", "SUPERVISOR", "CASHIER");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void billDocumentEndpointReturnsDownloadablePdfWithExpectedBusinessData() throws Exception {
        Debt debt = debt("PDF-ENDPOINT-1", "PDF-ENDPOINT-CONCEPT");
        Bill bill = billing.create(new ApiDtos.CreateBillRequest(
            debt.taxpayerId,
            List.of(debt.id),
            LocalDate.now().plusDays(10)
        ));

        MvcResult result = mvc.perform(get("/api/v1/bills/{id}/document", bill.id))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename="" + bill.number + ".pdf""
            ))
            .andReturn();

        byte[] pdf = result.getResponse().getContentAsByteArray();
        String printablePdf = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(pdf).isNotEmpty();
        assertThat(printablePdf)
            .startsWith("%PDF-1.4")
            .contains(bill.number)
            .contains("100.00");

        authenticate("AUDITOR");
        mvc.perform(get("/api/v1/bills/{id}/document", bill.id))
            .andExpect(status().isForbidden());
    }

    @Test
    void massRunPreviewsFiftyValidItemsAndOneControlledErrorBeforeSingleExecution() {
        assertTimeout(Duration.ofSeconds(30), () -> {
            TaxConcept concept = concept("MASS-ACCEPTANCE");
            activate(concept.id);

            List<ApiDtos.LiquidationRunItemRequest> items = new ArrayList<>();
            for (int index = 0; index < 50; index++) {
                TaxpayerReference taxpayer = taxpayer("MASS-" + index);
                items.add(new ApiDtos.LiquidationRunItemRequest(taxpayer.id, BigDecimal.ZERO));
            }
            items.add(new ApiDtos.LiquidationRunItemRequest(9_000_000_000_000_000_000L, BigDecimal.ZERO));

            long beforePreview = liquidationRepository.count();
            LiquidationRun run = runs.create(new ApiDtos.CreateLiquidationRunRequest(
                concept.id,
                YearMonth.now().toString(),
                LocalDate.now().plusDays(30),
                items
            ));

            ApiDtos.LiquidationRunDetail preview = runs.preview(run.id);
            assertThat(preview.run().totalItems).isEqualTo(51);
            assertThat(preview.run().validItems).isEqualTo(50);
            assertThat(preview.run().errorItems).isEqualTo(1);
            assertThat(preview.items())
                .filteredOn(item -> item.status == LiquidationRunItemStatus.VALID)
                .hasSize(50);
            assertThat(preview.items())
                .filteredOn(item -> item.status == LiquidationRunItemStatus.ERROR)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.errorCode).isNotBlank();
                    assertThat(item.liquidationId).isNull();
                });
            assertThat(liquidationRepository.count()).isEqualTo(beforePreview);

            runs.submit(run.id);
            runs.approve(run.id, "Aceptación automática");
            runs.execute(run.id);

            ApiDtos.LiquidationRunDetail executed = runs.detail(run.id);
            assertThat(executed.run().status).isEqualTo(LiquidationRunStatus.EXECUTED);
            assertThat(executed.items())
                .filteredOn(item -> item.status == LiquidationRunItemStatus.LIQUIDATED)
                .hasSize(50)
                .allSatisfy(item -> assertThat(item.liquidationId).isNotNull());
            assertThat(executed.items())
                .filteredOn(item -> item.status == LiquidationRunItemStatus.ERROR)
                .hasSize(1);
            assertThat(liquidationRepository.findAll())
                .filteredOn(liquidation -> concept.id.equals(liquidation.taxConceptId))
                .hasSize(50);

            assertThatThrownBy(() -> runs.execute(run.id))
                .isInstanceOf(BusinessException.class);
            assertThat(liquidationRepository.findAll())
                .filteredOn(liquidation -> concept.id.equals(liquidation.taxConceptId))
                .hasSize(50);
        });
    }

    private void authenticate(String... roles) {
        var authorities = List.of(roles).stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new AuthenticatedIdentity("qa-automation", null),
                null,
                authorities
            )
        );
    }

    private Debt debt(String externalId, String conceptCode) {
        TaxpayerReference taxpayer = taxpayer(externalId);
        TaxConcept concept = concept(conceptCode);
        activate(concept.id);
        liquidations.create(new ApiDtos.LiquidationRequest(
            taxpayer.id,
            concept.id,
            YearMonth.now().toString(),
            BigDecimal.ZERO,
            LocalDate.now().plusDays(30)
        ));
        return debts.findByTaxpayerId(taxpayer.id).get(0);
    }

    private TaxpayerReference taxpayer(String externalId) {
        return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.CITIZEN,
            externalId,
            externalId.replaceAll("[^0-9]", ""),
            null,
            "Persona " + externalId
        ));
    }

    private TaxConcept concept(String code) {
        return catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
            code,
            code,
            null,
            TaxConceptType.FEE,
            "M5"
        ));
    }

    private void activate(Long conceptId) {
        TaxConfiguration configuration = catalog.createConfiguration(
            new ApiDtos.CreateTaxConfigurationRequest(
                conceptId,
                CalculationType.FIXED,
                null,
                new BigDecimal("100"),
                null,
                null,
                true,
                true,
                LocalDate.now().minusDays(1),
                null
            )
        );
        catalog.submit(configuration.id);
        catalog.approve(configuration.id);
    }
}
