package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class BillingFlowTests {
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired BillingService billing;
    @Autowired PdfDocumentService documents;
    @Autowired PaymentService payments;
    @Autowired ElectronicPaymentService electronicPayments;
    @Autowired CreditBalanceService creditService;
    @Autowired DebtRepository debts;
    @Autowired CreditBalanceRepository credits;

    @BeforeEach void authenticateEmployee() {
        authenticate(new AuthenticatedIdentity("tester", null), "RENTAS", "CASHIER");
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void issuingBillDoesNotMutateDebtAndProducesPdf() {
        Debt debt = debt("BILL-1", "BILLABLE");

        Bill bill = billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId, List.of(debt.id), LocalDate.now().plusDays(10)));

        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
        assertThat(bill.totalAmount).isEqualByComparingTo("100.00");
        assertThat(new String(documents.bill(billing.detail(bill.id)), StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.4");
    }

    @Test void billRejectsDebtsFromAnotherTaxpayer() {
        Debt one = debt("BILL-2", "BILL-ONE");
        Debt two = debt("BILL-3", "BILL-TWO");

        assertThatThrownBy(() -> billing.create(new ApiDtos.CreateBillRequest(one.taxpayerId, List.of(one.id, two.id), LocalDate.now().plusDays(10))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("contribuyente");
    }

    @Test void unallocatedPaymentCanBeAllocatedLater() {
        Debt debt = debt("BILL-4", "ALLOCATE-LATER");
        Payment payment = payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId, PaymentMethod.CASH, new BigDecimal("100"), List.of()));

        payments.allocateExisting(payment.id, new ApiDtos.AllocationRequest(debt.id, new BigDecimal("100")));

        assertThat(payment.allocationStatus).isEqualTo(PaymentAllocationStatus.FULLY_ALLOCATED);
        assertThat(payment.unallocatedAmount).isEqualByComparingTo("0.00");
        assertThat(debts.findById(debt.id).orElseThrow().status).isEqualTo(DebtStatus.PAID);
    }

    @Test void overpaymentPreservesAllocationBreakdownAndCannotBeSpentTwice() {
        Debt debt = debt("BILL-5", "OVERPAY-CREDIT");

        Payment payment = payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId, PaymentMethod.CASH, new BigDecimal("120"), List.of(new ApiDtos.AllocationRequest(debt.id, new BigDecimal("120")))));

        assertThat(payment.allocatedAmount).isEqualByComparingTo("100.00");
        assertThat(payment.unallocatedAmount).isEqualByComparingTo("20.00");
        assertThat(payment.allocationStatus).isEqualTo(PaymentAllocationStatus.PARTIALLY_ALLOCATED);
        assertThat(credits.findBySourcePaymentId(payment.id).orElseThrow().availableAmount).isEqualByComparingTo("20.00");
        assertThatThrownBy(() -> payments.allocateExisting(payment.id, new ApiDtos.AllocationRequest(debt.id, new BigDecimal("20"))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("saldo a favor");
    }

    @Test void creditCanBeAppliedToAnotherDebtOfSameTaxpayer() {
        Debt first = debt("BILL-6", "CREDIT-SOURCE");
        TaxConcept secondConcept = concept("CREDIT-TARGET");
        activate(secondConcept.id);
        liquidations.create(liquidation(first.taxpayerId, secondConcept.id));
        Debt second = debts.findByTaxpayerId(first.taxpayerId).stream().filter(x -> !x.id.equals(first.id)).findFirst().orElseThrow();
        Payment payment = payments.register(new ApiDtos.RegisterPaymentRequest(first.taxpayerId, PaymentMethod.CASH, new BigDecimal("120"), List.of(new ApiDtos.AllocationRequest(first.id, new BigDecimal("120")))));

        creditService.apply(credits.findBySourcePaymentId(payment.id).orElseThrow().id, new ApiDtos.ApplyCreditBalanceRequest(second.id, new BigDecimal("20")));

        assertThat(debts.findById(second.id).orElseThrow().outstandingBalance).isEqualByComparingTo("80.00");
        assertThat(credits.findBySourcePaymentId(payment.id).orElseThrow().status).isEqualTo(CreditBalanceStatus.USED);
    }

    @Test void electronicPaymentUsesTaxpayerIdentityAndElectronicOrigin() {
        Debt debt = debt("BILL-7", "ELECTRONIC");
        authenticate(new AuthenticatedIdentity("taxpayer-user", debt.taxpayerId), "TAXPAYER");

        ElectronicPaymentAttempt attempt = electronicPayments.create(new ApiDtos.ElectronicPaymentRequest(debt.id, PaymentMethod.CARD, new BigDecimal("100")));

        assertThat(attempt.status).isEqualTo(ElectronicPaymentStatus.APPROVED);
        assertThat(electronicPayments.getByPayment(attempt.paymentId).id).isEqualTo(attempt.id);
        assertThat(debts.findById(debt.id).orElseThrow().status).isEqualTo(DebtStatus.PAID);
    }

    private void authenticate(AuthenticatedIdentity identity, String... roles) {
        var authorities = List.of(roles).stream().map(x -> new SimpleGrantedAuthority("ROLE_" + x)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, authorities));
    }

    private Debt debt(String externalId, String conceptCode) {
        String uniqueDni = externalId.substring(externalId.lastIndexOf('-') + 1);
        TaxpayerReference taxpayer = catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN, externalId, uniqueDni, null, "Persona " + externalId));
        TaxConcept concept = concept(conceptCode);
        activate(concept.id);
        liquidations.create(liquidation(taxpayer.id, concept.id));
        return debts.findByTaxpayerId(taxpayer.id).get(0);
    }

    private TaxConcept concept(String code) {
        return catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(code, code, null, TaxConceptType.FEE, "M5"));
    }

    private void activate(Long conceptId) {
        TaxConfiguration configuration = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(conceptId, CalculationType.FIXED, null, new BigDecimal("100"), null, null, true, true, LocalDate.now().minusDays(1), null));
        catalog.submit(configuration.id);
        catalog.approve(configuration.id);
    }

    private ApiDtos.LiquidationRequest liquidation(Long taxpayerId, Long conceptId) {
        return new ApiDtos.LiquidationRequest(taxpayerId, conceptId, YearMonth.now().toString(), BigDecimal.ZERO, LocalDate.now().plusDays(30));
    }
}
