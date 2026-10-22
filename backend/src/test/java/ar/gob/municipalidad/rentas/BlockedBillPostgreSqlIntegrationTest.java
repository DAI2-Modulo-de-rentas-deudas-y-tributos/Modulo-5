package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BlockedBillPostgreSqlIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired DebtRepository debts;
    @Autowired BillRepository bills;
    @Autowired BillDebtRepository billDebts;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoUserRepository demoUsers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;

    @BeforeEach
    void authenticateFixtureSetup() {
        var authorities = List.of("RENTAS", "SUPERVISOR").stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedIdentity("scrum-668-test", null), null, authorities));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void taxpayerBlockedGets422WithoutPersistingBillOrLinks() throws Exception {
        Debt blockedDebt = debtFor(TaxpayerStatus.BLOCKED);
        long billsBefore = bills.count();
        long linksBefore = billDebts.count();

        mvc.perform(post("/api/v1/bills")
                        .header("X-Demo-Session", session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(blockedDebt)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TAXPAYER_BLOCKED"));

        assertThat(bills.count()).isEqualTo(billsBefore);
        assertThat(billDebts.count()).isEqualTo(linksBefore);
        assertThat(jdbc.queryForObject("select count(*) from bill where taxpayer_id = ?", Long.class, blockedDebt.taxpayerId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bill_debt bd join bill b on b.id = bd.bill_id where b.taxpayer_id = ?", Long.class, blockedDebt.taxpayerId)).isZero();
    }

    @Test
    void activeTaxpayerCanIssueBillAndPersistsItsDebtLink() throws Exception {
        Debt activeDebt = debtFor(TaxpayerStatus.ACTIVE);
        mvc.perform(post("/api/v1/bills")
                        .header("X-Demo-Session", session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(activeDebt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxpayerId").value(activeDebt.taxpayerId));

        assertThat(jdbc.queryForObject("select count(*) from bill where taxpayer_id = ?", Long.class, activeDebt.taxpayerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from bill_debt bd join bill b on b.id = bd.bill_id where b.taxpayer_id = ?", Long.class, activeDebt.taxpayerId)).isEqualTo(1);
    }

    private Debt debtFor(TaxpayerStatus status) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        TaxpayerReference taxpayer = new TaxpayerReference();
        taxpayer.taxpayerType = TaxpayerType.CITIZEN;
        taxpayer.externalId = "SCRUM-668-" + suffix;
        taxpayer.dni = suffix.substring(0, 8);
        taxpayer.displayName = "SCRUM-668 " + status;
        taxpayer.externalStatus = status;
        taxpayer.createdAt = taxpayer.updatedAt = OffsetDateTime.now();
        taxpayer = taxpayers.saveAndFlush(taxpayer);

        TaxConcept concept = catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
                "SCRUM_668_" + suffix, "SCRUM 668 " + suffix, null, TaxConceptType.FEE, "M5"));
        var configuration = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
                concept.id, CalculationType.FIXED, null, new BigDecimal("100"), null, null,
                true, true, LocalDate.now().minusDays(1), null));
        catalog.submit(configuration.id);
        catalog.approve(configuration.id);
        var liquidation = liquidations.create(new ApiDtos.LiquidationRequest(
                taxpayer.id, concept.id, YearMonth.now().toString(), BigDecimal.ZERO, LocalDate.now().plusDays(30)));
        return debts.findByTaxpayerId(taxpayer.id).stream()
                .filter(debt -> debt.liquidationId.equals(liquidation.id))
                .findFirst().orElseThrow();
    }

    private String session() {
        DemoAuthSessions sessions = new DemoAuthSessions(demoUsers, taxpayers, encoder, demoAuth);
        return sessions.token(DemoRole.RENTAS);
    }

    private String request(Debt debt) {
        return "{\"taxpayerId\":" + debt.taxpayerId + ",\"debtIds\":[" + debt.id + "],\"dueDate\":\"" + LocalDate.now().plusDays(10) + "\"}";
    }
}
