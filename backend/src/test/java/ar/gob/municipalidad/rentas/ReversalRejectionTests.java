package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReversalRejectionTests {
    @Autowired MockMvc mvc;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired PaymentService payments;
    @Autowired ReversalService reversals;
    @Autowired DebtRepository debts;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentAllocationRepository allocations;
    @Autowired PaymentReversalRepository reversalRepository;
    @Autowired AuditRepository audit;
    @Autowired DemoUserRepository demoUsers;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;

    private String supervisorSession;

    @BeforeEach
    void authenticate() {
        supervisorSession = new DemoAuthSessions(demoUsers, taxpayers, encoder, demoAuth).token(DemoRole.SUPERVISOR);
        var authorities = List.of("CASHIER", "SUPERVISOR").stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("requester", null), null, authorities));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectionPersistsAndExposesSeparateReasonsWithoutEconomicChanges() throws Exception {
        Debt debt = debt("REJECT-WITH-REASON");
        Payment payment = pay(debt);
        PaymentReversalRequest request = reversals.request(payment.id, "Motivo original de solicitud");

        mvc.perform(post("/api/v1/payment-reversals/{id}/reject", request.id)
                .header("X-Demo-Session", supervisorSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Comprobante válido: no corresponde revertir\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reason").value("Motivo original de solicitud"))
            .andExpect(jsonPath("$.resolutionReason").value("Comprobante válido: no corresponde revertir"))
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.resolvedBy").isNotEmpty())
            .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        PaymentReversalRequest stored = reversalRepository.findById(request.id).orElseThrow();
        assertThat(stored.status).isEqualTo(PaymentReversalStatus.REJECTED);
        assertThat(stored.reason).isEqualTo("Motivo original de solicitud");
        assertThat(stored.resolutionReason).isEqualTo("Comprobante válido: no corresponde revertir");
        assertThat(stored.resolvedBy).isNotBlank();
        assertThat(stored.resolvedAt).isNotNull();
        assertEconomyUnchanged(payment, debt);
        assertThat(audit.findByEntityTypeAndEntityIdOrderByOccurredAt("PaymentReversal", request.id.toString()))
            .anySatisfy(entry -> {
                assertThat(entry.action).isEqualTo("PAYMENT_REVERSAL_REJECTED");
                assertThat(entry.newData).contains("Comprobante válido: no corresponde revertir");
            });
    }

    @Test
    void blankReasonIsRejectedByServiceAndApi() throws Exception {
        Debt debt = debt("REJECT-BLANK");
        Payment payment = pay(debt);
        PaymentReversalRequest request = reversals.request(payment.id, "Motivo original");

        assertThatThrownBy(() -> reversals.reject(request.id, "   "))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code).isEqualTo("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/payment-reversals/{id}/reject", request.id)
                .header("X-Demo-Session", supervisorSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"   \"}"))
            .andExpect(status().isBadRequest());

        PaymentReversalRequest stored = reversalRepository.findById(request.id).orElseThrow();
        assertThat(stored.status).isEqualTo(PaymentReversalStatus.PENDING_APPROVAL);
        assertThat(stored.resolutionReason).isNull();
        assertThat(stored.resolvedBy).isNull();
        assertThat(stored.resolvedAt).isNull();
        assertEconomyUnchanged(payment, debt);
    }

    @Test
    void approvalKeepsResolutionReasonNull() {
        Debt debt = debt("APPROVE-WITHOUT-REJECTION-REASON");
        Payment payment = pay(debt);
        PaymentReversalRequest request = reversals.request(payment.id, "Pago duplicado");

        PaymentReversalRequest approved = reversals.approve(request.id);

        assertThat(approved.status).isEqualTo(PaymentReversalStatus.APPROVED);
        assertThat(approved.resolutionReason).isNull();
        assertThat(approved.resolvedBy).isNotBlank();
        assertThat(approved.resolvedAt).isNotNull();
        assertEconomyUnchanged(payment, debt);
    }

    private void assertEconomyUnchanged(Payment payment, Debt debt) {
        assertThat(paymentRepository.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(debts.findById(debt.id).orElseThrow()).satisfies(storedDebt -> {
            assertThat(storedDebt.status).isEqualTo(DebtStatus.PAID);
            assertThat(storedDebt.outstandingBalance).isZero();
        });
        assertThat(allocations.findByPaymentId(payment.id)).singleElement()
            .satisfies(allocation -> assertThat(allocation.status).isEqualTo("ACTIVE"));
    }

    private Payment pay(Debt debt) {
        return payments.register(new ApiDtos.RegisterPaymentRequest(
            debt.taxpayerId, PaymentMethod.CASH, new BigDecimal("100"),
            List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("100")))));
    }

    private Debt debt(String code) {
        TaxpayerReference taxpayer = catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
            TaxpayerType.CITIZEN, "TAXPAYER-" + code, "20-00000000-1", null, "Persona " + code));
        TaxConcept concept = catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
            code, code, null, TaxConceptType.FEE, "M5"));
        TaxConfiguration configuration = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            concept.id, CalculationType.FIXED, null, new BigDecimal("100"), null, null, true, true,
            LocalDate.now().minusDays(1), null));
        catalog.submit(configuration.id);
        catalog.approve(configuration.id);
        liquidations.create(new ApiDtos.LiquidationRequest(
            taxpayer.id, concept.id, YearMonth.now().toString(), BigDecimal.ZERO, LocalDate.now().plusDays(30)));
        return debts.findByTaxpayerId(taxpayer.id).get(0);
    }
}
