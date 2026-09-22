package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BackendFinalCleanupTests {
    @Autowired MockMvc mvc;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired BillingService billing;
    @Autowired TaxpayerQueryService taxpayerQueries;
    @Autowired AdjustmentService adjustments;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;
    @Autowired DebtRepository debts;
    @Autowired BillRepository bills;
    @Autowired BillDebtRepository billDebts;
    @Autowired PaymentRepository payments;
    @Autowired DemoUserRepository demoUsers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;
    @Autowired FilteredQueryService queries;
    private DemoAuthSessions demo;

    @BeforeEach void setup() {
        authenticate(new AuthenticatedIdentity("cleanup-test",null),"RENTAS","SUPERVISOR","CASHIER","AUDITOR");
        demo=new DemoAuthSessions(demoUsers,taxpayers,encoder,demoAuth);
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void blockedTaxpayerCannotIssueBillAndRejectionHasNoSideEffects() throws Exception {
        Debt debt=liquidatedDebt("BLOCKED-BILL");
        TaxpayerReference taxpayer=taxpayers.findById(debt.taxpayerId).orElseThrow();
        taxpayer.externalStatus=TaxpayerStatus.BLOCKED;taxpayers.saveAndFlush(taxpayer);
        long billsBefore=bills.count(),linksBefore=billDebts.count();BigDecimal balance=debt.outstandingBalance;

        assertThatThrownBy(()->billing.create(new ApiDtos.CreateBillRequest(taxpayer.id,List.of(debt.id),LocalDate.now().plusDays(10))))
            .isInstanceOfSatisfying(BusinessException.class,error->{assertThat(error.code).isEqualTo("TAXPAYER_BLOCKED");assertThat(error.status).isEqualTo(422);});
        assertThat(bills.count()).isEqualTo(billsBefore);assertThat(billDebts.count()).isEqualTo(linksBefore);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo(balance);

        String session=demo.token(DemoRole.RENTAS);SecurityContextHolder.clearContext();
        mvc.perform(post("/api/v1/bills").header("X-Demo-Session",session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taxpayerId\":"+taxpayer.id+",\"debtIds\":["+debt.id+"],\"dueDate\":\""+LocalDate.now().plusDays(10)+"\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TAXPAYER_BLOCKED"));
        assertThat(bills.count()).isEqualTo(billsBefore);assertThat(billDebts.count()).isEqualTo(linksBefore);
    }

    @Test void activeTaxpayerCanStillIssueBill() {
        Debt debt=liquidatedDebt("ACTIVE-BILL");
        Bill bill=billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,List.of(debt.id),LocalDate.now().plusDays(10)));
        assertThat(bill.taxpayerId).isEqualTo(debt.taxpayerId);
        assertThat(billDebts.findByBillId(bill.id)).singleElement().satisfies(link->assertThat(link.debtId).isEqualTo(debt.id));
    }

    @Test void liquidationDetailExposesDebtOriginHistoryAndAdjustments() throws Exception {
        Debt debt=liquidatedDebt("LIQUIDATION-DETAIL");
        AdjustmentRequest adjustment=adjustments.create(new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.SURCHARGE,new BigDecimal("12.50"),"Recargo documentado"));
        adjustments.approve(adjustment.id,"Aprobado para auditoría");

        String session=demo.token(DemoRole.AUDITOR);SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/liquidations/{id}",debt.liquidationId).header("X-Demo-Session",session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(debt.liquidationId))
            .andExpect(jsonPath("$.debt.id").value(debt.id))
            .andExpect(jsonPath("$.origin.type").value("LIQUIDATION"))
            .andExpect(jsonPath("$.origin.module").value("M5"))
            .andExpect(jsonPath("$.historyOrder").value("ASC"))
            .andExpect(jsonPath("$.history[?(@.type == 'LIQUIDATION_CHANGE')]").isNotEmpty())
            .andExpect(jsonPath("$.history[?(@.type == 'DEBT_ADJUSTMENT_RESOLVED')]").isNotEmpty())
            .andExpect(jsonPath("$.adjustments[0].reason").value("Recargo documentado"))
            .andExpect(jsonPath("$.adjustments[0].resolutionReason").value("Aprobado para auditoría"));
    }

    @Test void exigibleDebtSummaryIncludesOnlyPendingAndPartiallyPaidPositiveBalances() {
        TaxpayerReference taxpayer=taxpayer("DEBT-SUMMARY",TaxpayerStatus.ACTIVE);TaxConcept concept=concept("DEBT_SUMMARY");
        debt(taxpayer,concept,DebtStatus.PENDING,"100",LocalDate.now().plusDays(2));
        debt(taxpayer,concept,DebtStatus.PENDING,"40",LocalDate.now().minusDays(2));
        debt(taxpayer,concept,DebtStatus.PARTIALLY_PAID,"60",LocalDate.now().plusDays(2));
        debt(taxpayer,concept,DebtStatus.PAID,"0",LocalDate.now().minusDays(2));
        debt(taxpayer,concept,DebtStatus.CANCELLED,"75",LocalDate.now().minusDays(2));

        ApiDtos.DebtSummaryResponse summary=taxpayerQueries.debtSummary(taxpayer.id);
        assertThat(summary.totalDebts()).isEqualTo(5);assertThat(summary.openDebts()).isEqualTo(3);
        assertThat(summary.paidDebts()).isEqualTo(1);assertThat(summary.overdueDebts()).isEqualTo(1);
        assertThat(summary.outstandingAmount()).isEqualByComparingTo("200.00");
        assertThat(taxpayerQueries.summary(taxpayer.id).outstandingDebt()).isEqualByComparingTo("200.00");
    }

    @Test void unallocatedPaymentsAreConfirmedAndSupportExactFiltersAndPaging() throws Exception {
        TaxpayerReference owner=taxpayer("UNALLOCATED-OWNER",TaxpayerStatus.ACTIVE);
        TaxpayerReference other=taxpayer("UNALLOCATED-OTHER",TaxpayerStatus.ACTIVE);
        OffsetDateTime todayAtNoon=LocalDate.now().atTime(12,0).atOffset(ZoneOffset.ofHours(-3));
        Payment expected=payment(owner,"UNALLOC-EXACT",PaymentStatus.CONFIRMED,"125.00","25.00",todayAtNoon);
        payment(owner,"UNALLOC-REVERSED",PaymentStatus.REVERSED,"125.00","25.00",todayAtNoon);
        payment(owner,"UNALLOC-ZERO",PaymentStatus.CONFIRMED,"125.00","0.00",todayAtNoon);
        payment(other,"UNALLOC-OTHER",PaymentStatus.CONFIRMED,"125.00","25.00",todayAtNoon);
        String session=demo.token(DemoRole.RENTAS),today=LocalDate.now().toString();SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/payments/unallocated").header("X-Demo-Session",session)
                .param("taxpayerId",owner.id.toString()).param("amount","125.00").param("reference",expected.receiptNumber)
                .param("from",today).param("to",today).param("page","0").param("size","1").param("sort","paidAt,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(expected.id)).andExpect(jsonPath("$.content[0].unallocatedAmount").value(25.00));
        mvc.perform(get("/api/v1/payments/unallocated").header("X-Demo-Session",session).param("reference","UNALLOC"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(0));
        mvc.perform(get("/api/v1/payments/unallocated").header("X-Demo-Session",session).param("status","REVERSED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test void billNumberFilterIsExactAndKeepsExistingPermissions() throws Exception {
        Debt first=liquidatedDebt("BILL-NUMBER-A"),second=liquidatedDebt("BILL-NUMBER-B");
        Bill expected=billing.create(new ApiDtos.CreateBillRequest(first.taxpayerId,List.of(first.id),LocalDate.now().plusDays(10)));
        Bill other=billing.create(new ApiDtos.CreateBillRequest(second.taxpayerId,List.of(second.id),LocalDate.now().plusDays(10)));
        assertThat(queries.list(bills,Bill.class,Map.of("number",expected.number),PageRequest.of(0,10)).getContent())
            .singleElement().satisfies(found->assertThat(found.id).isEqualTo(expected.id));
        String cashier=demo.token(DemoRole.CASHIER),taxpayerSession=demo.token(DemoRole.TAXPAYER,first.taxpayerId);SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/bills").header("X-Demo-Session",cashier).param("number",expected.number))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(expected.id)).andExpect(jsonPath("$.content[0].number").value(expected.number));
        mvc.perform(get("/api/v1/bills").header("X-Demo-Session",cashier).param("number",expected.number.substring(0,expected.number.length()-2)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(0));
        mvc.perform(get("/api/v1/bills").header("X-Demo-Session",cashier).param("number","BILL-NOT-FOUND"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(0));
        mvc.perform(get("/api/v1/bills").header("X-Demo-Session",taxpayerSession).param("number",other.number))
            .andExpect(status().isForbidden());
    }

    private Debt liquidatedDebt(String suffix) {
        TaxpayerReference taxpayer=taxpayer(suffix,TaxpayerStatus.ACTIVE);TaxConcept concept=concept("CONCEPT_"+suffix);
        TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));
        catalog.submit(configuration.id);catalog.approve(configuration.id);
        liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));
        return debts.findByTaxpayerId(taxpayer.id).get(0);
    }

    private TaxpayerReference taxpayer(String suffix,TaxpayerStatus status) {
        TaxpayerReference taxpayer=new TaxpayerReference();taxpayer.taxpayerType=TaxpayerType.CITIZEN;taxpayer.externalId=suffix+"-"+UUID.randomUUID();taxpayer.dni=String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()));taxpayer.displayName=suffix;taxpayer.externalStatus=status;taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();return taxpayers.save(taxpayer);
    }

    private TaxConcept concept(String code) {
        return catalog.createConcept(new ApiDtos.CreateTaxConceptRequest((code+UUID.randomUUID()).replace("-","_"),code,null,TaxConceptType.FEE,"M5"));
    }

    private Debt debt(TaxpayerReference taxpayer,TaxConcept concept,DebtStatus status,String outstanding,LocalDate dueDate) {
        Debt debt=new Debt();debt.taxpayerId=taxpayer.id;debt.taxConceptId=concept.id;debt.originType=DebtOriginType.LIQUIDATION;debt.liquidationId=Math.abs(UUID.randomUUID().getMostSignificantBits());debt.originalAmount=new BigDecimal("100.00");debt.currentAmount=new BigDecimal("100.00");debt.outstandingBalance=new BigDecimal(outstanding).setScale(2);debt.dueDate=dueDate;debt.status=status;debt.createdAt=debt.updatedAt=OffsetDateTime.now();return debts.save(debt);
    }

    private Payment payment(TaxpayerReference taxpayer,String receipt,PaymentStatus status,String amount,String unallocated,OffsetDateTime paidAt) {
        Payment payment=new Payment();payment.taxpayerId=taxpayer.id;payment.paymentMethod=PaymentMethod.CASH;payment.amount=new BigDecimal(amount);payment.unallocatedAmount=new BigDecimal(unallocated);payment.allocatedAmount=payment.amount.subtract(payment.unallocatedAmount);payment.status=status;payment.allocationStatus=payment.unallocatedAmount.signum()==0?PaymentAllocationStatus.FULLY_ALLOCATED:PaymentAllocationStatus.PARTIALLY_ALLOCATED;payment.origin=PaymentOrigin.CASHIER;payment.receiptNumber=receipt+"-"+UUID.randomUUID();payment.registeredBy="cleanup-test";payment.paidAt=paidAt;payment.createdAt=paidAt;return payments.save(payment);
    }

    private void authenticate(AuthenticatedIdentity identity,String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,Arrays.stream(roles).map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList()));
    }
}
