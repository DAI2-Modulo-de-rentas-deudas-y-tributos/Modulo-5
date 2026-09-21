package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ReversalEligibilityTests {
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired PaymentService payments;
    @Autowired ReversalService reversals;
    @Autowired CreditBalanceService creditBalances;
    @Autowired DebtRepository debts;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentAllocationRepository allocations;
    @Autowired CreditBalanceRepository creditRepository;
    @Autowired PaymentReversalRepository reversalRepository;

    @BeforeEach
    void authenticate() {
        var authorities = List.of("RENTAS", "SUPERVISOR", "CASHIER").stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("tester", null), null, authorities));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reversiblePaymentCreatesPendingApprovalRequestWithoutEconomicChanges() {
        Debt debt = debt("REVERSIBLE", null);
        Payment payment = pay(debt, "100");

        PaymentReversalRequest request = reversals.request(payment.id, "Pago duplicado");

        assertThat(request.status).isEqualTo(PaymentReversalStatus.PENDING_APPROVAL);
        assertThat(paymentRepository.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isZero();
        assertThat(allocations.findByPaymentId(payment.id)).singleElement()
            .satisfies(allocation -> assertThat(allocation.status).isEqualTo("ACTIVE"));
    }

    @Test
    void requestIsRejectedWhenGeneratedCreditWasUsedAndLeavesEconomyUnchanged() {
        UsedCreditFixture fixture = paymentWithUsedCredit("USED-BEFORE-REQUEST");

        assertThatThrownBy(() -> reversals.request(fixture.payment.id, "Pago duplicado"))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code).isEqualTo("CREDIT_BALANCE_ALREADY_USED"));

        assertUsedCreditEconomyUnchanged(fixture);
        assertThat(reversalRepository.findAll()).noneMatch(request -> fixture.payment.id.equals(request.paymentId));
    }

    @Test
    void duplicateActiveRequestRemainsRejected() {
        Debt debt = debt("DUPLICATE-REQUEST", null);
        Payment payment = pay(debt, "100");
        reversals.request(payment.id, "Primera solicitud");

        assertThatThrownBy(() -> reversals.request(payment.id, "Solicitud duplicada"))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code).isEqualTo("REVERSAL_ALREADY_EXISTS"));

        assertThat(reversalRepository.findAll().stream().filter(request -> payment.id.equals(request.paymentId))).hasSize(1);
    }

    @Test
    void executeRevalidatesWhenGeneratedCreditIsUsedAfterApproval() {
        Debt source = debt("USED-AFTER-APPROVAL", null);
        Payment payment = pay(source, "120");
        PaymentReversalRequest request = reversals.request(payment.id, "Pago duplicado");
        reversals.approve(request.id);
        UsedCreditFixture fixture = useGeneratedCredit(source, payment, "USED-AFTER-APPROVAL-TARGET");

        assertThatThrownBy(() -> reversals.execute(request.id))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code).isEqualTo("CREDIT_BALANCE_ALREADY_USED"));

        assertThat(reversalRepository.findById(request.id).orElseThrow().status).isEqualTo(PaymentReversalStatus.APPROVED);
        assertUsedCreditEconomyUnchanged(fixture);
    }

    private UsedCreditFixture paymentWithUsedCredit(String code) {
        Debt source = debt(code + "-SOURCE", null);
        Payment payment = pay(source, "120");
        return useGeneratedCredit(source, payment, code + "-TARGET");
    }

    private UsedCreditFixture useGeneratedCredit(Debt source, Payment payment, String targetCode) {
        Debt target = debt(targetCode, source.taxpayerId);
        CreditBalance credit = creditRepository.findBySourcePaymentId(payment.id).orElseThrow();
        creditBalances.apply(credit.id, new ApiDtos.ApplyCreditBalanceRequest(target.id, new BigDecimal("5")));
        return new UsedCreditFixture(source, target, payment, credit);
    }

    private void assertUsedCreditEconomyUnchanged(UsedCreditFixture fixture) {
        assertThat(paymentRepository.findById(fixture.payment.id).orElseThrow().status).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(debts.findById(fixture.source.id).orElseThrow().outstandingBalance).isZero();
        assertThat(debts.findById(fixture.target.id).orElseThrow().outstandingBalance).isEqualByComparingTo("95.00");
        assertThat(allocations.findByPaymentId(fixture.payment.id)).singleElement()
            .satisfies(allocation -> assertThat(allocation.status).isEqualTo("ACTIVE"));
        assertThat(creditRepository.findById(fixture.credit.id).orElseThrow()).satisfies(credit -> {
            assertThat(credit.availableAmount).isEqualByComparingTo("15.00");
            assertThat(credit.status).isEqualTo(CreditBalanceStatus.PARTIALLY_USED);
        });
    }

    private Payment pay(Debt debt, String amount) {
        return payments.register(new ApiDtos.RegisterPaymentRequest(
            debt.taxpayerId, PaymentMethod.CASH, new BigDecimal(amount),
            List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal(amount)))));
    }

    private Debt debt(String code, Long taxpayerId) {
        Long ownerId = taxpayerId;
        if (ownerId == null) {
            TaxpayerReference taxpayer = catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(
                TaxpayerType.CITIZEN, "TAXPAYER-" + code, "20-00000000-1", null, "Persona " + code));
            ownerId = taxpayer.id;
        }
        TaxConcept concept = catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(
            code, code, null, TaxConceptType.FEE, "M5"));
        TaxConfiguration configuration = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            concept.id, CalculationType.FIXED, null, new BigDecimal("100"), null, null, true, true,
            LocalDate.now().minusDays(1), null));
        catalog.submit(configuration.id);
        catalog.approve(configuration.id);
        liquidations.create(new ApiDtos.LiquidationRequest(
            ownerId, concept.id, YearMonth.now().toString(), BigDecimal.ZERO, LocalDate.now().plusDays(30)));
        Long finalOwnerId = ownerId;
        return debts.findByTaxpayerId(finalOwnerId).stream()
            .filter(debt -> concept.id.equals(debt.taxConceptId))
            .findFirst()
            .orElseThrow();
    }

    private record UsedCreditFixture(Debt source, Debt target, Payment payment, CreditBalance credit) {}
}
