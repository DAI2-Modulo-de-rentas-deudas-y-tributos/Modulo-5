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
    @Autowired ApiController api;
    @Autowired DebtRepository debts;
    @Autowired CreditBalanceRepository credits;
    @Autowired CreditBalanceApplicationRepository creditApplications;
    @Autowired AuditRepository audits;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentAllocationRepository paymentAllocations;
    @Autowired ElectronicPaymentRepository electronicPaymentAttempts;

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
        CreditBalance credit = credits.findBySourcePaymentId(payment.id).orElseThrow();
        assertThat(credit.availableAmount).isEqualByComparingTo("20.00");
        AuditEntry creation = audits.findByEntityTypeAndEntityIdOrderByOccurredAt("CreditBalance", credit.id.toString()).stream()
            .filter(entry -> entry.action.equals("CREDIT_BALANCE_CREATED")).findFirst().orElseThrow();
        assertThat(creation.newData)
            .contains("\"id\":" + credit.id, "\"taxpayerId\":" + credit.taxpayerId,
                "\"sourcePaymentId\":" + payment.id, "\"originalAmount\":20.00");
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

    @Test void creditApplicationsRemainTraceableAfterPartialAndFullUse() {
        Debt source = debt("BILL-CREDIT-TRACE-SOURCE", "CREDIT-TRACE-SOURCE");
        Debt firstTarget = anotherDebt(source, "CREDIT-TRACE-TARGET-1");
        TaxConcept secondTargetConcept = concept("CREDIT-TRACE-TARGET-2");
        activate(secondTargetConcept.id);
        liquidations.create(liquidation(source.taxpayerId, secondTargetConcept.id));
        Debt secondTarget = debts.findByTaxpayerId(source.taxpayerId).stream()
            .filter(debt -> !debt.id.equals(source.id) && !debt.id.equals(firstTarget.id)).findFirst().orElseThrow();
        Payment payment = payments.register(new ApiDtos.RegisterPaymentRequest(source.taxpayerId, PaymentMethod.CASH,
            new BigDecimal("140"), List.of(new ApiDtos.AllocationRequest(source.id, new BigDecimal("140")))));
        CreditBalance credit = credits.findBySourcePaymentId(payment.id).orElseThrow();

        CreditBalanceApplication first = creditService.apply(credit.id,
            new ApiDtos.ApplyCreditBalanceRequest(firstTarget.id, new BigDecimal("10")));
        assertThat(credits.findById(credit.id).orElseThrow().status).isEqualTo(CreditBalanceStatus.PARTIALLY_USED);
        assertThat(api.creditBalanceApplications(credit.id)).singleElement().satisfies(application -> {
            assertThat(application.id()).isEqualTo(first.id);
            assertThat(application.debtId()).isEqualTo(firstTarget.id);
            assertThat(application.amount()).isEqualByComparingTo("10.00");
            assertThat(application.appliedBy()).isEqualTo("tester");
            assertThat(application.appliedAt()).isNotNull();
        });

        CreditBalanceApplication second = creditService.apply(credit.id,
            new ApiDtos.ApplyCreditBalanceRequest(secondTarget.id, new BigDecimal("30")));
        List<CreditBalanceApplication> history = creditService.applications(credit.id);

        assertThat(credits.findById(credit.id).orElseThrow().status).isEqualTo(CreditBalanceStatus.USED);
        assertThat(history).extracting(application -> application.id).containsExactly(first.id, second.id);
        assertThat(history).extracting(application -> application.debtId).containsExactly(firstTarget.id, secondTarget.id);
        assertThat(history).extracting(application -> application.amount)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(new BigDecimal("10.00"), new BigDecimal("30.00"));
        assertThat(history).allSatisfy(application -> {
            assertThat(application.status).isEqualTo("ACTIVE");
            assertThat(application.appliedBy).isEqualTo("tester");
            assertThat(application.appliedAt).isNotNull();
        });
    }

    @Test void creditApplicationHistoryEnforcesOwnershipAndAllowsAuditor() {
        Debt source = debt("BILL-CREDIT-TRACE-OWNER", "CREDIT-TRACE-OWNER");
        Debt target = anotherDebt(source, "CREDIT-TRACE-OWNER-TARGET");
        Payment payment = payments.register(new ApiDtos.RegisterPaymentRequest(source.taxpayerId, PaymentMethod.CASH,
            new BigDecimal("120"), List.of(new ApiDtos.AllocationRequest(source.id, new BigDecimal("120")))));
        CreditBalance credit = credits.findBySourcePaymentId(payment.id).orElseThrow();
        creditService.apply(credit.id, new ApiDtos.ApplyCreditBalanceRequest(target.id, new BigDecimal("10")));
        Debt foreign = debt("BILL-CREDIT-TRACE-FOREIGN", "CREDIT-TRACE-FOREIGN");

        authenticate(new AuthenticatedIdentity("owner", source.taxpayerId), "TAXPAYER");
        assertThat(api.creditBalanceApplications(credit.id)).hasSize(1);

        authenticate(new AuthenticatedIdentity("foreign", foreign.taxpayerId), "TAXPAYER");
        assertThatThrownBy(() -> api.creditBalanceApplications(credit.id))
            .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.code).isEqualTo("FORBIDDEN_OWNERSHIP"));

        authenticate(new AuthenticatedIdentity("auditor", null), "AUDITOR");
        assertThat(api.creditBalanceApplications(credit.id)).hasSize(1);
        assertThatThrownBy(() -> api.creditBalanceApplications(Long.MAX_VALUE))
            .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.code).isEqualTo("NOT_FOUND"));
    }

    @Test void creditCannotBeAppliedToCancelledDebtAndLeavesBalancesUnchanged() {
        Debt source = debt("BILL-CREDIT-CANCELLED-SOURCE", "CREDIT-CANCELLED-SOURCE");
        Debt target = anotherDebt(source, "CREDIT-CANCELLED-TARGET");
        Payment payment = payments.register(new ApiDtos.RegisterPaymentRequest(source.taxpayerId, PaymentMethod.CASH, new BigDecimal("120"), List.of(new ApiDtos.AllocationRequest(source.id, new BigDecimal("120")))));
        CreditBalance credit = credits.findBySourcePaymentId(payment.id).orElseThrow();
        target.status = DebtStatus.CANCELLED;
        debts.save(target);

        assertThatThrownBy(() -> creditService.apply(credit.id, new ApiDtos.ApplyCreditBalanceRequest(target.id, new BigDecimal("20"))))
            .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.code).isEqualTo("DEBT_NOT_PAYABLE"));

        CreditBalance storedCredit = credits.findById(credit.id).orElseThrow();
        Debt storedTarget = debts.findById(target.id).orElseThrow();
        assertThat(storedCredit.availableAmount).isEqualByComparingTo("20.00");
        assertThat(storedCredit.status).isEqualTo(CreditBalanceStatus.AVAILABLE);
        assertThat(storedTarget.outstandingBalance).isEqualByComparingTo("100.00");
        assertThat(storedTarget.status).isEqualTo(DebtStatus.CANCELLED);
        assertThat(creditApplications.findAll()).noneMatch(application -> credit.id.equals(application.creditBalanceId));
    }

    @Test void creditCannotBeAppliedToPaidDebtAndLeavesBalancesUnchanged() {
        Debt source = debt("BILL-CREDIT-PAID-SOURCE", "CREDIT-PAID-SOURCE");
        Debt target = anotherDebt(source, "CREDIT-PAID-TARGET");
        Payment payment = payments.register(new ApiDtos.RegisterPaymentRequest(source.taxpayerId, PaymentMethod.CASH, new BigDecimal("120"), List.of(new ApiDtos.AllocationRequest(source.id, new BigDecimal("120")))));
        CreditBalance credit = credits.findBySourcePaymentId(payment.id).orElseThrow();
        target.status = DebtStatus.PAID;
        target.outstandingBalance = BigDecimal.ZERO.setScale(2);
        debts.save(target);

        assertThatThrownBy(() -> creditService.apply(credit.id, new ApiDtos.ApplyCreditBalanceRequest(target.id, new BigDecimal("20"))))
            .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.code).isEqualTo("DEBT_NOT_PAYABLE"));

        CreditBalance storedCredit = credits.findById(credit.id).orElseThrow();
        Debt storedTarget = debts.findById(target.id).orElseThrow();
        assertThat(storedCredit.availableAmount).isEqualByComparingTo("20.00");
        assertThat(storedCredit.status).isEqualTo(CreditBalanceStatus.AVAILABLE);
        assertThat(storedTarget.outstandingBalance).isZero();
        assertThat(storedTarget.status).isEqualTo(DebtStatus.PAID);
        assertThat(creditApplications.findAll()).noneMatch(application -> credit.id.equals(application.creditBalanceId));
    }

    @Test void electronicPaymentUsesTaxpayerIdentityAndElectronicOrigin() {
        Debt debt = debt("BILL-7", "ELECTRONIC");
        authenticate(new AuthenticatedIdentity("taxpayer-user", debt.taxpayerId), "TAXPAYER");

        ElectronicPaymentAttempt attempt = electronicPayments.create(new ApiDtos.ElectronicPaymentRequest(debt.id, PaymentMethod.CARD, new BigDecimal("100")));

        assertThat(attempt.status).isEqualTo(ElectronicPaymentStatus.APPROVED);
        assertThat(electronicPayments.getByPayment(attempt.paymentId).id).isEqualTo(attempt.id);
        assertThat(debts.findById(debt.id).orElseThrow().status).isEqualTo(DebtStatus.PAID);
    }

    @Test void rejectedElectronicAttemptIsPersistedWithoutEconomicEffectsAndRemainsIdempotent() {
        Debt debt=debt("BILL-ELECTRONIC-REJECTED","ELECTRONIC-REJECTED");
        debt.status=DebtStatus.CANCELLED;debts.saveAndFlush(debt);
        authenticate(new AuthenticatedIdentity("taxpayer-user",debt.taxpayerId),"TAXPAYER");
        long paymentCount=paymentRepository.count(),allocationCount=paymentAllocations.count();
        ApiDtos.ElectronicPaymentRequest request=new ApiDtos.ElectronicPaymentRequest(debt.id,PaymentMethod.CARD,new BigDecimal("100"));

        ElectronicPaymentAttempt first=electronicPayments.create(request,"rejected-attempt-1");
        ElectronicPaymentAttempt repeated=electronicPayments.create(request,"rejected-attempt-1");

        assertThat(first.status).isEqualTo(ElectronicPaymentStatus.REJECTED);
        assertThat(first.paymentId).isNull();
        assertThat(first.rejectionReason).isNotBlank();
        assertThat(repeated.id).isEqualTo(first.id);
        assertThat(paymentRepository.count()).isEqualTo(paymentCount);
        assertThat(paymentAllocations.count()).isEqualTo(allocationCount);
        assertThat(debts.findById(debt.id).orElseThrow()).satisfies(stored->{
            assertThat(stored.status).isEqualTo(DebtStatus.CANCELLED);
            assertThat(stored.outstandingBalance).isEqualByComparingTo("100.00");
        });
        assertThat(electronicPaymentAttempts.findAll()).filteredOn(attempt->debt.id.equals(attempt.debtId)).hasSize(1);
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

    private Debt anotherDebt(Debt source, String conceptCode) {
        TaxConcept concept = concept(conceptCode);
        activate(concept.id);
        liquidations.create(liquidation(source.taxpayerId, concept.id));
        return debts.findByTaxpayerId(source.taxpayerId).stream().filter(debt -> !debt.id.equals(source.id)).findFirst().orElseThrow();
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
