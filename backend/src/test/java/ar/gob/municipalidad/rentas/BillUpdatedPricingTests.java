package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:bill-updated-pricing;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@Transactional
class BillUpdatedPricingTests {
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired LiquidationRepository liquidationRepository;
    @Autowired BillingService billing;
    @Autowired PaymentService payments;
    @Autowired AdjustmentService adjustmentService;
    @Autowired LateChargeService lateCharges;
    @Autowired LateChargeRuleRepository rules;
    @Autowired LateChargeApplicationRepository applications;
    @Autowired AdjustmentRepository adjustments;
    @Autowired DebtRepository debts;
    @Autowired BillRepository bills;
    @Autowired BillDebtRepository billDebts;
    @Autowired MockMvc mvc;
    @Autowired DemoUserRepository demoUsers;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;

    @BeforeEach void authenticateEmployee() { authenticate(new AuthenticatedIdentity("bill-pricing-tester",null),"RENTAS","CASHIER","SUPERVISOR"); }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void freshBillKeepsSnapshotAndUsesCurrentOutstanding() {
        Debt debt=debt("FRESH");Bill bill=bill(debt);

        ApiDtos.BillDetailResponse detail=billing.pricingDetail(bill.id);

        assertThat(detail.totalAmount()).isEqualByComparingTo("100");
        assertThat(detail.debts()).singleElement().satisfies(link->assertThat(link.amountAtIssue()).isEqualByComparingTo("100"));
        assertThat(detail.updatedPayableAmount()).isEqualByComparingTo("100");
        assertThat(detail.pricingDetails()).singleElement().satisfies(price->{assertThat(price.outstandingBalance()).isEqualByComparingTo("100");assertThat(price.pendingInterestAmount()).isZero();assertThat(price.pendingSurchargeAmount()).isZero();});
        assertThat(bills.findById(bill.id).orElseThrow().totalAmount).isEqualByComparingTo("100");
        assertThat(billDebts.findByBillId(bill.id).get(0).amountAtIssue).isEqualByComparingTo("100");
    }

    @Test void partialPaymentUsesOutstandingInsteadOfAmountAtIssue() {
        Debt debt=debt("PARTIAL");Bill bill=bill(debt);
        payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,new BigDecimal("40"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40")))));

        ApiDtos.BillDebtPricingResponse price=billing.pricingDetail(bill.id).pricingDetails().get(0);

        assertThat(price.amountAtIssue()).isEqualByComparingTo("100");
        assertThat(price.outstandingBalance()).isEqualByComparingTo("60");
        assertThat(price.updatedPayableAmount()).isEqualByComparingTo("60");
    }

    @Test void approvedAdjustmentsAreExplanatoryAndNotCountedTwice() {
        Debt debt=debt("ADJUSTED");Bill bill=bill(debt);
        approveAdjustment(debt,AdjustmentType.DISCOUNT,"10");
        approveAdjustment(debt,AdjustmentType.SURCHARGE,"5");
        approveAdjustment(debt,AdjustmentType.INTEREST,"3");
        approveAdjustment(debt,AdjustmentType.CORRECTION,"120");

        ApiDtos.BillDebtPricingResponse price=billing.pricingDetail(bill.id).pricingDetails().get(0);

        assertThat(price.appliedDiscountAmount()).isEqualByComparingTo("10");
        assertThat(price.appliedSurchargeAmount()).isEqualByComparingTo("5");
        assertThat(price.appliedInterestAmount()).isEqualByComparingTo("3");
        assertThat(price.appliedCorrectionAmount()).isEqualByComparingTo("22");
        assertThat(price.outstandingBalance()).isEqualByComparingTo("120");
        assertThat(price.updatedPayableAmount()).isEqualByComparingTo("120");
    }

    @Test void overdueDebtShowsPendingChargesWithoutMutatingAnythingOnRepeatedReads() {
        Debt debt=debt("PENDING-LATE");debt.dueDate=LocalDate.now().minusDays(5);debts.saveAndFlush(debt);Bill bill=bill(debt);rule("PENDING-RULE","10","1");
        long applicationCount=applications.count(),adjustmentCount=adjustments.count();BigDecimal current=debt.currentAmount,outstanding=debt.outstandingBalance;

        ApiDtos.BillDetailResponse first=billing.pricingDetail(bill.id);ApiDtos.BillDetailResponse second=billing.pricingDetail(bill.id);

        ApiDtos.BillDebtPricingResponse price=first.pricingDetails().get(0);
        assertThat(price.pendingSurchargeAmount()).isEqualByComparingTo("10");
        assertThat(price.pendingInterestAmount()).isEqualByComparingTo("5");
        assertThat(price.updatedPayableAmount()).isEqualByComparingTo("115");
        assertThat(second.updatedPayableAmount()).isEqualByComparingTo(first.updatedPayableAmount());
        assertThat(applications.count()).isEqualTo(applicationCount);
        assertThat(adjustments.count()).isEqualTo(adjustmentCount);
        assertThat(debts.findById(debt.id).orElseThrow()).satisfies(stored->{assertThat(stored.currentAmount).isEqualByComparingTo(current);assertThat(stored.outstandingBalance).isEqualByComparingTo(outstanding);});
    }

    @Test void appliedLateChargeIsExplainedButNotQuotedAgainForSameDate() {
        Debt debt=debt("APPLIED-LATE");debt.dueDate=LocalDate.now().minusDays(5);debts.saveAndFlush(debt);Bill bill=bill(debt);rule("APPLIED-RULE","10","1");
        lateCharges.apply(debt.id,LocalDate.now());

        ApiDtos.BillDebtPricingResponse price=billing.pricingDetail(bill.id).pricingDetails().get(0);

        assertThat(price.appliedSurchargeAmount()).isEqualByComparingTo("10");
        assertThat(price.appliedInterestAmount()).isEqualByComparingTo("5");
        assertThat(price.pendingSurchargeAmount()).isZero();
        assertThat(price.pendingInterestAmount()).isZero();
        assertThat(price.outstandingBalance()).isEqualByComparingTo("115");
        assertThat(price.updatedPayableAmount()).isEqualByComparingTo("115");
    }

    @Test void noRuleAndNotOverdueReturnZeroPendingCharges() {
        Debt overdue=debt("NO-RULE");overdue.dueDate=LocalDate.now().minusDays(2);debts.saveAndFlush(overdue);Bill overdueBill=bill(overdue);
        Debt current=debt("NOT-OVERDUE");Bill currentBill=bill(current);

        assertThat(billing.pricingDetail(overdueBill.id).pricingDetails().get(0).pendingInterestAmount()).isZero();
        assertThat(billing.pricingDetail(currentBill.id).pricingDetails().get(0).pendingSurchargeAmount()).isZero();
    }

    @Test void paidAndCancelledDebtsNeverGeneratePendingCharges() {
        rule("STATUS-RULE","10","1");
        Debt paid=debt("PAID");paid.dueDate=LocalDate.now().minusDays(3);debts.saveAndFlush(paid);Bill paidBill=bill(paid);payments.register(new ApiDtos.RegisterPaymentRequest(paid.taxpayerId,PaymentMethod.CASH,new BigDecimal("100"),List.of(new ApiDtos.AllocationRequest(paid.id,new BigDecimal("100")))));
        Debt cancelled=debt("CANCELLED");cancelled.dueDate=LocalDate.now().minusDays(3);debts.saveAndFlush(cancelled);Bill cancelledBill=bill(cancelled);cancelled.status=DebtStatus.CANCELLED;debts.saveAndFlush(cancelled);

        assertThat(billing.pricingDetail(paidBill.id).pricingDetails().get(0)).satisfies(price->{assertThat(price.pendingSurchargeAmount()).isZero();assertThat(price.pendingInterestAmount()).isZero();});
        assertThat(billing.pricingDetail(cancelledBill.id).pricingDetails().get(0)).satisfies(price->{assertThat(price.pendingSurchargeAmount()).isZero();assertThat(price.pendingInterestAmount()).isZero();});
    }

    @Test void multipleDebtsAreSummedAndIssueCompositionComesFromLiquidation() {
        Debt first=debt("MULTI-1");Debt second=anotherDebt(first,"MULTI-2");Liquidation liquidation=liquidationRepository.findById(first.liquidationId).orElseThrow();liquidation.discountAmount=new BigDecimal("7");liquidation.exemptionAmount=new BigDecimal("3");liquidation.surchargeAmount=new BigDecimal("2");liquidation.interestAmount=new BigDecimal("1");liquidationRepository.saveAndFlush(liquidation);Bill bill=billing.create(new ApiDtos.CreateBillRequest(first.taxpayerId,List.of(first.id,second.id),LocalDate.now().plusDays(10)));payments.register(new ApiDtos.RegisterPaymentRequest(first.taxpayerId,PaymentMethod.CASH,new BigDecimal("40"),List.of(new ApiDtos.AllocationRequest(first.id,new BigDecimal("40")))));

        ApiDtos.BillDetailResponse detail=billing.pricingDetail(bill.id);ApiDtos.BillDebtPricingResponse firstPrice=detail.pricingDetails().stream().filter(x->x.debtId().equals(first.id)).findFirst().orElseThrow();

        assertThat(detail.totalAmount()).isEqualByComparingTo("200");
        assertThat(detail.updatedPayableAmount()).isEqualByComparingTo("160");
        assertThat(firstPrice.issueDiscountAmount()).isEqualByComparingTo("7");
        assertThat(firstPrice.issueExemptionAmount()).isEqualByComparingTo("3");
        assertThat(firstPrice.issueSurchargeAmount()).isEqualByComparingTo("2");
        assertThat(firstPrice.issueInterestAmount()).isEqualByComparingTo("1");
    }

    @Test void ownerCanReadAndForeignTaxpayerIsRejected() throws Exception {
        Debt debt=debt("OWNERSHIP");Bill bill=bill(debt);TaxpayerReference foreign=taxpayer("FOREIGN");DemoAuthSessions sessions=sessions();String owner=sessions.token(DemoRole.TAXPAYER,debt.taxpayerId),stranger=sessions.token(DemoRole.TAXPAYER,foreign.id);SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/bills/{id}",bill.id).header("X-Demo-Session",owner)).andExpect(status().isOk()).andExpect(jsonPath("$.updatedPayableAmount").value(100));
        mvc.perform(get("/api/v1/bills/{id}",bill.id).header("X-Demo-Session",stranger)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
    }

    @ParameterizedTest @EnumSource(value=DemoRole.class,names={"CASHIER","RENTAS","AUDITOR"})
    void internalRolesCanReadUpdatedDetail(DemoRole role) throws Exception {
        Debt debt=debt("ROLE-"+role);Bill bill=bill(debt);String token=sessions().token(role);SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/bills/{id}",bill.id).header("X-Demo-Session",token)).andExpect(status().isOk()).andExpect(jsonPath("$.pricingDetails.length()").value(1));
    }

    @Test void missingBillReturnsNotFound() throws Exception {
        String token=sessions().token(DemoRole.RENTAS);SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/bills/{id}",Long.MAX_VALUE).header("X-Demo-Session",token)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private Bill bill(Debt debt){return billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,List.of(debt.id),LocalDate.now().plusDays(10)));}
    private void approveAdjustment(Debt debt,AdjustmentType type,String amount){AdjustmentRequest request=adjustmentService.create(new ApiDtos.CreateAdjustmentRequest(debt.id,type,new BigDecimal(amount),"Prueba de pricing"));adjustmentService.approve(request.id,"Aprobado");}
    private LateChargeRule rule(String code,String surcharge,String interest){LateChargeRule rule=new LateChargeRule();rule.code=code;rule.surchargeRate=new BigDecimal(surcharge);rule.dailyInterestRate=new BigDecimal(interest);rule.active=true;rule.validFrom=LocalDate.now().minusDays(30);return rules.save(rule);}
    private Debt debt(String suffix){TaxpayerReference taxpayer=taxpayer(suffix);return createDebt(taxpayer,suffix);}
    private Debt anotherDebt(Debt source,String suffix){return createDebt(taxpayers.findById(source.taxpayerId).orElseThrow(),suffix);}
    private Debt createDebt(TaxpayerReference taxpayer,String suffix){String unique=suffix+"-"+UUID.randomUUID().toString().substring(0,8);TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("BILL_"+unique,"Concepto "+unique,null,TaxConceptType.FEE,"M5"));TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(configuration.id);catalog.approve(configuration.id);liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));return debts.findByTaxpayerId(taxpayer.id).stream().filter(x->x.taxConceptId.equals(concept.id)).findFirst().orElseThrow();}
    private TaxpayerReference taxpayer(String suffix){String digits=String.valueOf(Math.abs((long)suffix.hashCode())+10000000L);return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"BILL-PRICE-"+suffix+"-"+UUID.randomUUID(),digits,null,"Contribuyente "+suffix));}
    private DemoAuthSessions sessions(){return new DemoAuthSessions(demoUsers,taxpayers,encoder,demoAuth);}
    private void authenticate(AuthenticatedIdentity identity,String... roles){var authorities=List.of(roles).stream().map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,authorities));}
}
