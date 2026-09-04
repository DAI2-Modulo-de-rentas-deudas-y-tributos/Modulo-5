package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.properties.hibernate.generate_statistics=true","spring.jpa.properties.hibernate.session.events.log=false"})
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PostgreSqlIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired PlanWorkflowService workflow;
    @Autowired PaymentService payments;
    @Autowired ReversalService reversals;
    @Autowired DebtRepository debts;
    @Autowired InstallmentRepository installments;
    @Autowired PaymentAllocationRepository allocations;
    @Autowired PaymentRepository paymentRepository;
    @Autowired CreditBalanceRepository creditRepository;
    @Autowired CreditBalanceApplicationRepository creditApplications;
    @Autowired CreditBalanceService credits;
    @Autowired PaymentPlanService plans;
    @Autowired PaymentPlanRepository planRepository;
    @Autowired OutboxRepository outbox;
    @Autowired PreliminaryM4Consumer m4;
    @Autowired ConfirmedM7Consumer m7;
    @Autowired IntegrationEventLogRepository integrationLogs;
    @Autowired ExternalObligationRepository externalObligations;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;
    @Autowired ObjectMapper json;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired LiquidationComponentRepository components;
    @Autowired ApiResponseService responses;
    @Autowired BillRepository bills;
    @Autowired BillDebtRepository billDebts;
    @Autowired BillingService billing;
    @Autowired IndicatorService indicators;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired LiquidationRunService bulkRuns;

    @AfterEach void clearSecurity(){SecurityContextHolder.clearContext();}

    @Test void contextStartsAndFlywayAppliesEveryMigration(){
        assertThat(jdbc.queryForObject("select max(cast(version as integer)) from flyway_schema_history where success",Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version is not null",Integer.class)).isEqualTo(12);
    }

    @Test void economicChecksAndIdempotencyUniquesExist(){
        List<String> checks=jdbc.queryForList("select pg_get_constraintdef(oid) from pg_constraint where contype='c'",String.class);
        assertThat(checks).anyMatch(x->x.contains("origin_type")&&x.contains("liquidation_id")&&x.contains("external_obligation_id"));
        assertThat(checks).anyMatch(x->x.contains("target_type")&&x.contains("debt_id")&&x.contains("installment_id"));
        assertThat(checks).anyMatch(x->x.contains("allocated_amount")&&x.contains("unallocated_amount")&&x.contains("amount"));
        assertThat(checks).anyMatch(x->x.contains("principal_applied")&&x.contains("interest_applied"));
        assertThat(checks).anyMatch(x->x.contains("available_amount")&&x.contains("original_amount"));
        List<String> uniques=jdbc.queryForList("select pg_get_constraintdef(oid) from pg_constraint where contype in ('p','u')",String.class);
        assertThat(uniques).anyMatch(x->x.contains("event_id"));
        assertThat(uniques).anyMatch(x->x.contains("taxpayer_id")&&x.contains("tax_concept_id")&&x.contains("period"));
        assertThat(uniques).anyMatch(x->x.contains("source_module")&&x.contains("external_type")&&x.contains("external_reference_id"));
    }

    @Test void recommendedOperationalIndexesExist(){
        List<String> indexes=jdbc.queryForList("select indexdef from pg_indexes where schemaname='public'",String.class);
        assertThat(indexes).anyMatch(x->x.contains("debt")&&x.contains("taxpayer_id"));
        assertThat(indexes).anyMatch(x->x.contains("payment")&&x.contains("paid_at"));
        assertThat(indexes).anyMatch(x->x.contains("integration_event_log")&&x.contains("status"));
        assertThat(indexes).anyMatch(x->x.contains("liquidation_component")&&x.contains("liquidation_id"));
        assertThat(indexes).anyMatch(x->x.contains("payment")&&x.contains("status")&&x.contains("paid_at"));
        assertThat(indexes).anyMatch(x->x.contains("payment_plan")&&x.contains("status"));
    }

    @Test void confirmedContractProjectionAndExternalEventIdConstraintsExist(){
        List<String> columns=jdbc.queryForList("select table_name||'.'||column_name from information_schema.columns where table_schema='public'",String.class);
        assertThat(columns).contains("processed_event.external_event_id","integration_event_log.external_event_id","social_benefit_reference.calculated_status","social_benefit_reference.benefits_payload","taxpayer_representation_reference.external_representation_id");
        List<String> uniques=jdbc.queryForList("select pg_get_constraintdef(oid) from pg_constraint where contype='u'",String.class);
        assertThat(uniques).anyMatch(x->x.contains("external_event_id"));
        assertThat(uniques).anyMatch(x->x.contains("external_representation_id"));
    }

    @Test void installmentPaymentAndReversalPreservePrincipalInterestBreakdown(){
        var authorities=List.of("RENTAS","SUPERVISOR","CASHIER").stream().map(x->new SimpleGrantedAuthority("ROLE_"+x)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("postgres-it",null),null,authorities));
        try {
            String suffix=UUID.randomUUID().toString();
            TaxpayerReference taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"PG-"+suffix,"1",null,"PostgreSQL IT"));
            TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("PG_"+suffix,"PostgreSQL IT",null,TaxConceptType.FEE,"M5"));
            TaxConfiguration taxConfiguration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("80"),null,null,true,true,LocalDate.now().minusDays(1),null));
            catalog.submit(taxConfiguration.id);catalog.approve(taxConfiguration.id);
            liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));
            Debt debt=debts.findByTaxpayerId(taxpayer.id).get(0);
            workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(1,1,BigDecimal.ZERO,new BigDecimal("25"),0,1,true,true,1,LocalDate.now().minusDays(1),null,true));
            PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(taxpayer.id,List.of(debt.id),1));
            PaymentPlanRequest granted=workflow.grant(request.id,null);
            Installment installment=installments.findByPaymentPlanIdOrderByNumber(granted.paymentPlanId).get(0);
            Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,null,PaymentMethod.CASH,new BigDecimal("100"),List.of(new ApiDtos.AllocationRequest(null,installment.id,new BigDecimal("100")))));
            PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
            assertThat(installment.principalAmount).isEqualByComparingTo("80.00");
            assertThat(installment.interestAmount).isEqualByComparingTo("20.00");
            assertThat(allocation.principalApplied).isEqualByComparingTo("80.00");
            assertThat(allocation.interestApplied).isEqualByComparingTo("20.00");
            assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isZero();
            PaymentReversalRequest reversal=reversals.request(payment.id,"Regresión PostgreSQL");reversals.approve(reversal.id);reversals.execute(reversal.id);
            assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("80.00");
            assertThat(allocations.findByPaymentId(payment.id).get(0).status).isEqualTo("REVERSED");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test void concurrentOutboxWorkersLockEachEventOnce() throws Exception {
        OutboxEvent event=new OutboxEvent();event.id=UUID.randomUUID();event.eventType="validation";event.targetModule="TEST";event.aggregateType="Validation";event.aggregateId=event.id.toString();event.payload="{}";event.status=OutboxStatus.PENDING;event.createdAt=java.time.OffsetDateTime.now();outbox.save(event);
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var task=(java.util.concurrent.Callable<Integer>)()->{ready.countDown();start.await();return new TransactionTemplate(transactionManager).execute(status->{var claimed=outbox.findPublishable(PageRequest.of(0,50));if(!claimed.isEmpty()){try{Thread.sleep(150);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}claimed.forEach(x->x.status=OutboxStatus.PUBLISHED);}return claimed.size();});};
            Future<Integer> first=pool.submit(task),second=pool.submit(task);assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)+second.get(10,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(outbox.findById(event.id).orElseThrow().status).isEqualTo(OutboxStatus.PUBLISHED);
        } finally { pool.shutdownNow(); }
    }

    @Test void preliminaryM4ContractsRunOnPostgreSqlWithoutEconomicEffects() throws Exception {
        String permit="{\"module\":\"M4\",\"event\":\"permitFeeGenerated\",\"data\":{\"id\":\"FEE-001\",\"permitApplicationId\":\"PA-001\",\"establishmentId\":\"EST-001\",\"amount\":50000}}";
        String fine="{\"module\":\"M4\",\"event\":\"commercialFineGenerated\",\"data\":{\"id\":\"FINE-001\",\"sourceViolationId\":\"V-001\",\"sourceModule\":\"M6\",\"establishmentId\":\"EST-001\",\"actId\":\"ACT-001\",\"amount\":100000,\"reason\":\"Incumplimiento comercial\",\"decidedAt\":\"2026-08-24T15:00:00-03:00\",\"externalRef\":\"MULTA-2026-00123\"}}";
        var feeEvent=json.readValue(permit,new TypeReference<PreliminaryM4Events.Envelope<PreliminaryM4Events.PermitFeeGeneratedData>>(){});
        var fineEvent=json.readValue(fine,new TypeReference<PreliminaryM4Events.Envelope<PreliminaryM4Events.CommercialFineGeneratedData>>(){});
        long obligationCount=externalObligations.count(),debtCount=debts.count();
        IntegrationEventLog fee=m4.consumePermitFee(feeEvent),duplicate=m4.consumePermitFee(feeEvent),fineLog=m4.consumeCommercialFine(fineEvent);
        assertThat(duplicate.id).isEqualTo(fee.id);assertThat(fee.errorMessage).startsWith(PreliminaryM4Consumer.TAXPAYER_BLOCKER);
        assertThat(fineLog.errorMessage).startsWith(PreliminaryM4Consumer.TAXPAYER_BLOCKER);assertThat(fineLog.payload).contains("V-001","M6","ACT-001","MULTA-2026-00123");
        assertThat(externalObligations.count()).isEqualTo(obligationCount);assertThat(debts.count()).isEqualTo(debtCount);
    }

    @Test void m7ContractIdempotencyResolutionRollbackAndConcurrencyRunOnPostgreSql() throws Exception {
        concept("TRAFFIC_INFRACTION",TaxConceptType.FINE,"M7");String dni=uniqueDigits(8),cuit=uniqueDigits(11);
        TaxpayerReference citizen=taxpayer(TaxpayerType.CITIZEN,"PG-M7-C-"+UUID.randomUUID(),dni,null),organization=taxpayer(TaxpayerType.ORGANIZATION,"PG-M7-O-"+UUID.randomUUID(),null,cuit);
        UUID infraction=UUID.randomUUID();var data=m7Data(infraction,dni,ConfirmedInboundEvents.M7DebtorIdType.DNI,new BigDecimal("75"));
        ExternalObligation first=m7.consume(m7Event(UUID.randomUUID(),"transito",data));
        ExternalObligation duplicate=m7.consume(m7Event(UUID.randomUUID(),"transito",data));
        assertThat(duplicate.id).isEqualTo(first.id);assertThat(first.sourceModule).isEqualTo("M7");assertThat(first.amount).isEqualByComparingTo("75.00");assertThat(first.taxpayerId).isEqualTo(citizen.id);
        assertThat(debts.findByTaxpayerId(citizen.id)).singleElement().satisfies(x->assertThat(x.originalAmount).isEqualByComparingTo("75.00"));
        var org=m7.consume(m7Event(UUID.randomUUID(),"transito",m7Data(UUID.randomUUID(),cuit,ConfirmedInboundEvents.M7DebtorIdType.CUIT,new BigDecimal("90"))));assertThat(org.taxpayerId).isEqualTo(organization.id);
        long beforeObligations=externalObligations.count(),beforeDebts=debts.count();
        assertThatThrownBy(()->m7.consume(m7Event(UUID.randomUUID(),"M7",m7Data(UUID.randomUUID(),dni,ConfirmedInboundEvents.M7DebtorIdType.DNI,BigDecimal.TEN)))).isInstanceOf(BusinessException.class);
        assertThat(externalObligations.count()).isEqualTo(beforeObligations);assertThat(debts.count()).isEqualTo(beforeDebts);
        UUID concurrentId=UUID.randomUUID();var concurrentData=m7Data(concurrentId,dni,ConfirmedInboundEvents.M7DebtorIdType.DNI,new BigDecimal("55"));
        List<Throwable> failures=runTogether(()->m7.consume(m7Event(UUID.randomUUID(),"transito",concurrentData)),()->m7.consume(m7Event(UUID.randomUUID(),"transito",concurrentData)));
        assertThat(failures.stream().filter(Objects::nonNull)).hasSizeLessThanOrEqualTo(1);
        assertThat(externalObligations.findAll().stream().filter(x->concurrentId.toString().equals(x.externalReferenceId))).hasSize(1);
        assertThat(debts.findByTaxpayerId(citizen.id).stream().filter(x->x.externalObligationId!=null&&x.externalObligationId.equals(externalObligations.findBySourceModuleAndExternalTypeAndExternalReferenceId("M7",ExternalObligationType.TRAFFIC_INFRACTION,concurrentId.toString()).orElseThrow().id))).hasSize(1);
    }

    @Test void paymentsOverpaymentsCreditAndLocksRunOnPostgreSql() throws Exception {
        authenticate();Debt total=debt("PG-TOTAL",null),partial=debt("PG-PARTIAL",null),over=debt("PG-OVER",null);
        Payment totalPayment=payments.register(new ApiDtos.RegisterPaymentRequest(total.taxpayerId,PaymentMethod.CASH,new BigDecimal("100"),List.of(new ApiDtos.AllocationRequest(total.id,new BigDecimal("100")))));
        Payment partialPayment=payments.register(new ApiDtos.RegisterPaymentRequest(partial.taxpayerId,PaymentMethod.CASH,new BigDecimal("40"),List.of(new ApiDtos.AllocationRequest(partial.id,new BigDecimal("40")))));
        Payment overPayment=payments.register(new ApiDtos.RegisterPaymentRequest(over.taxpayerId,PaymentMethod.CASH,new BigDecimal("120"),List.of(new ApiDtos.AllocationRequest(over.id,new BigDecimal("120")))));
        assertEquation(totalPayment);assertEquation(partialPayment);assertEquation(overPayment);
        assertThat(debts.findById(total.id).orElseThrow().outstandingBalance).isZero();assertThat(debts.findById(partial.id).orElseThrow().outstandingBalance).isEqualByComparingTo("60.00");
        CreditBalance credit=creditRepository.findBySourcePaymentId(overPayment.id).orElseThrow();assertThat(credit.originalAmount).isEqualByComparingTo("20.00");assertThat(credit.availableAmount).isEqualByComparingTo("20.00");
        Debt creditTarget=debt("PG-CREDIT",over.taxpayerId);credits.apply(credit.id,new ApiDtos.ApplyCreditBalanceRequest(creditTarget.id,new BigDecimal("20")));assertThat(creditRepository.findById(credit.id).orElseThrow().availableAmount).isZero();
        Debt concurrent=debt("PG-CONCURRENT",null);List<Throwable> paymentFailures=runTogether(()->payments.register(new ApiDtos.RegisterPaymentRequest(concurrent.taxpayerId,PaymentMethod.CASH,new BigDecimal("60"),List.of(new ApiDtos.AllocationRequest(concurrent.id,new BigDecimal("60"))))),()->payments.register(new ApiDtos.RegisterPaymentRequest(concurrent.taxpayerId,PaymentMethod.CASH,new BigDecimal("60"),List.of(new ApiDtos.AllocationRequest(concurrent.id,new BigDecimal("60"))))));
        assertThat(paymentFailures.stream().filter(Objects::nonNull)).isEmpty();assertThat(debts.findById(concurrent.id).orElseThrow().outstandingBalance).isZero();
        assertThat(allocations.findAll().stream().filter(x->concurrent.id.equals(x.debtId)&&"ACTIVE".equals(x.status)).map(x->x.amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("100.00");
    }

    @Test void planReversalAndCreditSingleExecutionLocksRunOnPostgreSql() throws Exception {
        authenticate();Debt planDebt=debt("PG-PLAN",null);List<Throwable> planFailures=runTogether(()->plans.grant(new ApiDtos.GrantPlanRequest(planDebt.id,3,BigDecimal.ZERO)),()->plans.grant(new ApiDtos.GrantPlanRequest(planDebt.id,3,BigDecimal.ZERO)));
        assertThat(planRepository.findAll().stream().filter(x->planDebt.id.equals(x.debtId)&&x.status==PaymentPlanStatus.ACTIVE)).hasSize(1);assertThat(planFailures.stream().filter(Objects::nonNull)).hasSize(1);
        Debt reversalDebt=debt("PG-REV",null);Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(reversalDebt.taxpayerId,PaymentMethod.CASH,new BigDecimal("120"),List.of(new ApiDtos.AllocationRequest(reversalDebt.id,new BigDecimal("120")))));PaymentReversalRequest reversal=reversals.request(payment.id,"PostgreSQL concurrente");reversals.approve(reversal.id);
        List<Throwable> reversalFailures=runTogether(()->reversals.execute(reversal.id),()->reversals.execute(reversal.id));assertThat(reversalFailures.stream().filter(Objects::nonNull)).hasSize(1);assertThat(paymentRepository.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.REVERSED);
        Debt source=debt("PG-CREDIT-SOURCE",null);Payment overPayment=payments.register(new ApiDtos.RegisterPaymentRequest(source.taxpayerId,PaymentMethod.CASH,new BigDecimal("120"),List.of(new ApiDtos.AllocationRequest(source.id,new BigDecimal("120")))));CreditBalance credit=creditRepository.findBySourcePaymentId(overPayment.id).orElseThrow();Debt first=debt("PG-CREDIT-A",source.taxpayerId),second=debt("PG-CREDIT-B",source.taxpayerId);
        List<Throwable> creditFailures=runTogether(()->credits.apply(credit.id,new ApiDtos.ApplyCreditBalanceRequest(first.id,new BigDecimal("15"))),()->credits.apply(credit.id,new ApiDtos.ApplyCreditBalanceRequest(second.id,new BigDecimal("15"))));assertThat(creditFailures.stream().filter(Objects::nonNull)).hasSize(1);assertThat(creditRepository.findById(credit.id).orElseThrow().availableAmount).isEqualByComparingTo("5.00");assertThat(creditApplications.findAll().stream().filter(x->credit.id.equals(x.creditBalanceId))).hasSize(1);
    }

    @Test void optimizedQueriesRemainBoundedOnPostgreSql(){
        authenticate();Debt first=debt("PG-PERF-A",null),second=debt("PG-PERF-B",first.taxpayerId);List<Liquidation> liquidationPage=new ArrayList<>();liquidationPage.add(liquidationFor(first));liquidationPage.add(liquidationFor(second));
        Statistics statistics=entityManagerFactory.unwrap(SessionFactory.class).getStatistics();statistics.clear();liquidations.responses(new PageImpl<>(liquidationPage));assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.clear();responses.debts(new PageImpl<>(List.of(first,second)));assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        Bill bill=billing.create(new ApiDtos.CreateBillRequest(first.taxpayerId,List.of(first.id,second.id),LocalDate.now().plusDays(10)));statistics.clear();responses.bills(new PageImpl<>(List.of(bill)));assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.clear();indicators.summary(null,null);assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        statistics.clear();workflow.defaulted(PageRequest.of(0,20));assertThat(statistics.getPrepareStatementCount()).isBetween(1L,2L);
    }

    @Test void concurrentBulkExecutionCommitsOnceOnPostgreSql() throws Exception {
        authenticate();
        TaxConcept concept = concept("PG_BULK_" + UUID.randomUUID(), TaxConceptType.FEE, "M5");
        TaxConfiguration config = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            concept.id, CalculationType.FIXED, null, new BigDecimal("100.25"), null, null,
            true, true, LocalDate.now().minusDays(1), null));
        catalog.submit(config.id); catalog.approve(config.id);
        List<ApiDtos.LiquidationRunItemRequest> population = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            TaxpayerReference owner = taxpayer(TaxpayerType.CITIZEN, "PG-BULK-" + UUID.randomUUID(), uniqueDigits(8), null);
            population.add(new ApiDtos.LiquidationRunItemRequest(owner.id, BigDecimal.ZERO));
        }
        LiquidationRun run = bulkRuns.create(new ApiDtos.CreateLiquidationRunRequest(concept.id,
            YearMonth.now().toString(), LocalDate.now().plusDays(30), population));
        bulkRuns.preview(run.id); bulkRuns.submit(run.id); bulkRuns.approve(run.id, "QA");
        List<Throwable> failures = runTogether(() -> bulkRuns.execute(run.id), () -> bulkRuns.execute(run.id));
        assertThat(failures.stream().filter(Objects::isNull)).hasSize(1);
        assertThat(failures.stream().filter(Objects::nonNull)).singleElement().satisfies(error ->
            assertThat(error).isInstanceOfSatisfying(BusinessException.class,
                business -> assertThat(business.code).isEqualTo("RUN_NOT_APPROVED")));
        assertThat(bulkRuns.detail(run.id).run().status).isEqualTo(LiquidationRunStatus.EXECUTED);
        assertThat(jdbc.queryForObject("select count(*) from liquidation where tax_concept_id=?", Long.class, concept.id)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from debt where tax_concept_id=?", Long.class, concept.id)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select sum(outstanding_balance) from debt where tax_concept_id=?", BigDecimal.class, concept.id)).isEqualByComparingTo("2005.00");
    }

    private void assertEquation(Payment payment){Payment stored=paymentRepository.findById(payment.id).orElseThrow();assertThat(stored.allocatedAmount.add(stored.unallocatedAmount)).isEqualByComparingTo(stored.amount);}
    private void authenticate(){var authorities=List.of("RENTAS","SUPERVISOR","CASHIER").stream().map(x->new SimpleGrantedAuthority("ROLE_"+x)).toList();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("postgres-it",null),null,authorities));}
    private Debt debt(String suffix,Long taxpayerId){if(taxpayerId==null)taxpayerId=taxpayer(TaxpayerType.CITIZEN,"PG-"+suffix+"-"+UUID.randomUUID(),uniqueDigits(8),null).id;TaxConcept concept=concept("PG_"+suffix+"_"+UUID.randomUUID(),TaxConceptType.FEE,"M5");TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(configuration.id);catalog.approve(configuration.id);Liquidation liquidation=liquidations.create(new ApiDtos.LiquidationRequest(taxpayerId,concept.id,YearMonth.now().plusMonths(Math.abs(suffix.hashCode()%12)).toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));return debts.findByTaxpayerId(taxpayerId).stream().filter(x->x.liquidationId.equals(liquidation.id)).findFirst().orElseThrow();}
    private Liquidation liquidationFor(Debt debt){return jdbc.queryForObject("select id from liquidation where id=?",(rs,n)->{Liquidation l=new Liquidation();l.id=rs.getLong(1);return l;},debt.liquidationId);}
    private TaxConcept concept(String code,TaxConceptType type,String module){return concepts.findByCode(code).orElseGet(()->catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(code,code,null,type,module)));}
    private TaxpayerReference taxpayer(TaxpayerType type,String external,String dni,String cuit){TaxpayerReference t=new TaxpayerReference();t.taxpayerType=type;t.externalId=external;t.dni=dni;t.cuit=cuit;t.displayName="PostgreSQL contract";t.externalStatus=TaxpayerStatus.ACTIVE;t.createdAt=t.updatedAt=java.time.OffsetDateTime.now();return taxpayers.save(t);}
    private String uniqueDigits(int length){String digits=Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());return (digits+"00000000000000000000").substring(0,length);}
    private ConfirmedInboundEvents.M7InfractionData m7Data(UUID id,String debtor,ConfirmedInboundEvents.M7DebtorIdType type,BigDecimal finalAmount){return new ConfirmedInboundEvents.M7InfractionData(id,debtor,type,"AA111AA","TEST",java.time.OffsetDateTime.now(),new BigDecimal("50"),List.of(),finalAmount,"INS-1",new ConfirmedInboundEvents.M7Location("Calle","Esquina",new BigDecimal("-34.60"),new BigDecimal("-58.40")));}
    private ConfirmedInboundEvents.M7InfractionConfirmedEvent m7Event(UUID eventId,String source,ConfirmedInboundEvents.M7InfractionData data){return new ConfirmedInboundEvents.M7InfractionConfirmedEvent(eventId,"infractionConfirmed",java.time.OffsetDateTime.now(),source,data);}
    private List<Throwable> runTogether(Callable<?> first,Callable<?> second)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);try{List<Future<Throwable>> futures=List.of(pool.submit(task(first,ready,start)),pool.submit(task(second,ready,start)));assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();List<Throwable> result=new ArrayList<>();for(Future<Throwable> future:futures)result.add(future.get(15,TimeUnit.SECONDS));return result;}finally{pool.shutdownNow();}}
    private Callable<Throwable> task(Callable<?> action,CountDownLatch ready,CountDownLatch start){return ()->{authenticate();ready.countDown();start.await();try{action.call();return null;}catch(Throwable ex){return ex;}finally{SecurityContextHolder.clearContext();}};}
}
