package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test") @SpringBootTest
class ConcurrencyTests {
    @Autowired CatalogService catalog; @Autowired LiquidationService liquidations; @Autowired PaymentService payments; @Autowired PaymentPlanService plans;
    @Autowired ReversalService reversals; @Autowired CreditBalanceService credits; @Autowired DebtRepository debtRepository; @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentAllocationRepository allocations; @Autowired PaymentPlanRepository planRepository; @Autowired CreditBalanceRepository creditRepository; @Autowired CreditBalanceApplicationRepository creditApplications;

    @BeforeEach void auth(){authenticate();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void simultaneousPaymentsCannotOverAllocateDebt(){Debt debt=debt("PAY");runTogether(()->payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,new BigDecimal("60"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("60"))))),()->payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,PaymentMethod.CASH,new BigDecimal("60"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("60"))))));assertThat(debtRepository.findById(debt.id).orElseThrow().outstandingBalance).isZero();assertThat(allocations.findAll().stream().filter(x->debt.id.equals(x.debtId)&&x.status.equals("ACTIVE")).map(x->x.amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("100.00");}

    @Test void simultaneousAllocationsCannotSpendPaymentTwice(){Debt first=debt("ALLOC-A");Debt second=debtFor(first.taxpayerId,"ALLOC-B");Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(first.taxpayerId,null,PaymentMethod.CASH,new BigDecimal("100"),null));runTogether(()->payments.allocateExisting(payment.id,new ApiDtos.AllocationRequest(first.id,new BigDecimal("80"))),()->payments.allocateExisting(payment.id,new ApiDtos.AllocationRequest(second.id,new BigDecimal("80"))));Payment stored=paymentRepository.findById(payment.id).orElseThrow();assertThat(stored.allocatedAmount).isEqualByComparingTo("100.00");assertThat(stored.unallocatedAmount).isZero();}

    @Test void simultaneousPlanGrantsLeaveOnlyOneActivePlan(){Debt debt=debt("PLAN");List<Throwable> failures=runTogether(()->plans.grant(new ApiDtos.GrantPlanRequest(debt.id,3,BigDecimal.ZERO)),()->plans.grant(new ApiDtos.GrantPlanRequest(debt.id,3,BigDecimal.ZERO)));assertThat(planRepository.findAll().stream().filter(x->debt.id.equals(x.debtId)&&x.status==PaymentPlanStatus.ACTIVE)).hasSize(1);assertThat(failures.stream().filter(Objects::nonNull)).hasSize(1);}

    @Test void reversalAndCreditApplicationAreSingleExecution(){Debt paidDebt=debt("REV");Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(paidDebt.taxpayerId,PaymentMethod.CASH,new BigDecimal("120"),List.of(new ApiDtos.AllocationRequest(paidDebt.id,new BigDecimal("120")))));PaymentReversalRequest reversal=reversals.request(payment.id,"Duplicado");reversals.approve(reversal.id);List<Throwable> reversalFailures=runTogether(()->reversals.execute(reversal.id),()->reversals.execute(reversal.id));assertThat(reversalFailures.stream().filter(Objects::nonNull)).hasSize(1);assertThat(paymentRepository.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.REVERSED);Debt source=debt("CREDIT-SOURCE");Payment overpayment=payments.register(new ApiDtos.RegisterPaymentRequest(source.taxpayerId,PaymentMethod.CASH,new BigDecimal("120"),List.of(new ApiDtos.AllocationRequest(source.id,new BigDecimal("120")))));CreditBalance credit=creditRepository.findBySourcePaymentId(overpayment.id).orElseThrow();Debt targetA=debtFor(source.taxpayerId,"CREDIT-A");Debt targetB=debtFor(source.taxpayerId,"CREDIT-B");List<Throwable> creditFailures=runTogether(()->credits.apply(credit.id,new ApiDtos.ApplyCreditBalanceRequest(targetA.id,new BigDecimal("15"))),()->credits.apply(credit.id,new ApiDtos.ApplyCreditBalanceRequest(targetB.id,new BigDecimal("15"))));assertThat(creditFailures.stream().filter(Objects::nonNull)).hasSize(1);assertThat(creditRepository.findById(credit.id).orElseThrow().availableAmount).isEqualByComparingTo("5.00");assertThat(creditApplications.findAll().stream().filter(x->credit.id.equals(x.creditBalanceId))).hasSize(1);}

    private List<Throwable> runTogether(Callable<?> first,Callable<?> second){ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);try{List<Future<Throwable>> futures=List.of(pool.submit(task(first,ready,start)),pool.submit(task(second,ready,start)));assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();List<Throwable> result=new ArrayList<>();for(Future<Throwable> future:futures)result.add(future.get(10,TimeUnit.SECONDS));return result;}catch(Exception ex){throw new AssertionError(ex);}finally{pool.shutdownNow();}}
    private Callable<Throwable> task(Callable<?> action,CountDownLatch ready,CountDownLatch start){return ()->{authenticate();ready.countDown();start.await();try{action.call();return null;}catch(Throwable ex){return ex;}finally{SecurityContextHolder.clearContext();}};}
    private void authenticate(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("concurrency",null),null,List.of(new SimpleGrantedAuthority("ROLE_RENTAS"),new SimpleGrantedAuthority("ROLE_SUPERVISOR"),new SimpleGrantedAuthority("ROLE_CASHIER"))));}
    private Debt debt(String suffix){TaxpayerReference taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"CONC-"+suffix+"-"+UUID.randomUUID(),"1",null,"Concurrente"));return debtFor(taxpayer.id,suffix);}
    private Debt debtFor(Long taxpayerId,String suffix){TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("CONC_"+suffix+"_"+UUID.randomUUID(),suffix,null,TaxConceptType.FEE,"M5"));TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(configuration.id);catalog.approve(configuration.id);liquidations.create(new ApiDtos.LiquidationRequest(taxpayerId,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(10)));return debtRepository.findByTaxpayerId(taxpayerId).stream().filter(x->x.taxConceptId.equals(concept.id)).findFirst().orElseThrow();}
}
