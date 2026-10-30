package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.properties.hibernate.generate_statistics=true","spring.jpa.properties.hibernate.session.events.log=false"})
@AutoConfigureMockMvc
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PostgreSqlIntegrationTest {
    @Autowired ExemptionService exenciones;
    @Autowired AuditRepository auditoria;

    @Test void pagosIdempotentesConcurrentesNoDuplicanEfectos() throws Exception {
        authenticate();
        Debt deuda=debt("IDEMPOTENCIA",null);
        var solicitud=new ApiDtos.RegisterPaymentRequest(deuda.taxpayerId,PaymentMethod.CASH,new BigDecimal("120"),List.of(new ApiDtos.AllocationRequest(deuda.id,new BigDecimal("100"))));
        String clave=UUID.randomUUID().toString();
        var resultados=new java.util.concurrent.CopyOnWriteArrayList<Long>();
        assertThat(runTogether(()->{resultados.add(payments.register(solicitud,clave).id);return null;},
            ()->{resultados.add(payments.register(solicitud,clave).id);return null;})).containsOnlyNulls();
        assertThat(resultados).hasSize(2);
        assertThat(resultados.get(0)).isEqualTo(resultados.get(1));
        assertThat(paymentRepository.findByTaxpayerId(deuda.taxpayerId)).hasSize(1);
        Payment pago=paymentRepository.findById(resultados.get(0)).orElseThrow();
        assertThat(pago.allocatedAmount).isEqualByComparingTo("100");
        assertThat(pago.unallocatedAmount).isEqualByComparingTo("20");
        assertThat(allocations.findByPaymentId(pago.id)).hasSize(1);
        assertThat(creditRepository.findBySourcePaymentId(pago.id).orElseThrow().availableAmount).isEqualByComparingTo("20");
        assertThat(debts.findById(deuda.id).orElseThrow().outstandingBalance).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type='paymentRegistered' and aggregate_id=?",Long.class,pago.id.toString())).isEqualTo(1);
        assertThat(auditoria.findByEntityTypeAndEntityIdOrderByOccurredAt("Payment",pago.id.toString())).hasSize(1);
        var cambiado=new ApiDtos.RegisterPaymentRequest(deuda.taxpayerId,PaymentMethod.CASH,new BigDecimal("121"),solicitud.allocations());
        assertThatThrownBy(()->payments.register(cambiado,clave)).isInstanceOfSatisfying(BusinessException.class,
            ex->{assertThat(ex.status).isEqualTo(409);assertThat(ex.code).isEqualTo("IDEMPOTENCY_KEY_REUSED");});
        assertThat(paymentRepository.findByTaxpayerId(deuda.taxpayerId)).hasSize(1);
    }

    @Test void boletasIdempotentesConcurrentesNoDuplicanBoletaNiRelaciones() throws Exception {
        authenticate();
        Debt deuda=debt("BOLETA-IDEMPOTENTE",null);
        var solicitud=new ApiDtos.CreateBillRequest(deuda.taxpayerId,List.of(deuda.id),LocalDate.now().plusDays(10));
        String clave=UUID.randomUUID().toString();
        var resultados=new java.util.concurrent.CopyOnWriteArrayList<Long>();
        assertThat(runTogether(()->{resultados.add(billing.create(solicitud,clave).id);return null;},
            ()->{resultados.add(billing.create(solicitud,clave).id);return null;})).containsOnlyNulls();
        assertThat(resultados).hasSize(2);
        assertThat(resultados.get(0)).isEqualTo(resultados.get(1));
        Bill boleta=bills.findById(resultados.get(0)).orElseThrow();
        assertThat(boleta.idempotencyKey).isEqualTo(clave);
        assertThat(boleta.idempotencyFingerprint).hasSize(64);
        assertThat(bills.findByTaxpayerId(deuda.taxpayerId)).hasSize(1);
        assertThat(billDebts.findByBillId(boleta.id)).singleElement().satisfies(link->{
            assertThat(link.debtId).isEqualTo(deuda.id);
            assertThat(link.amountAtIssue).isEqualByComparingTo("100.00");
        });
        var cambiado=new ApiDtos.CreateBillRequest(deuda.taxpayerId,List.of(deuda.id),LocalDate.now().plusDays(11));
        assertThatThrownBy(()->billing.create(cambiado,clave)).isInstanceOfSatisfying(BusinessException.class,
            ex->{assertThat(ex.status).isEqualTo(409);assertThat(ex.code).isEqualTo("IDEMPOTENCY_KEY_REUSED");});
        assertThat(bills.findByTaxpayerId(deuda.taxpayerId)).hasSize(1);
        assertThat(billDebts.findByBillId(boleta.id)).hasSize(1);
    }

    @Test void intentosElectronicosRechazadosConLaMismaClaveSonIdempotentesBajoConcurrencia() throws Exception {
        authenticate();
        Debt deuda=debt("ELECTRONICO-IDEMPOTENTE",null);
        ApiDtos.ElectronicPaymentRequest solicitud=new ApiDtos.ElectronicPaymentRequest(deuda.id,PaymentMethod.CASH,new BigDecimal("25"));
        String clave=UUID.randomUUID().toString();
        String sesionA=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuthService).token(DemoRole.TAXPAYER,deuda.taxpayerId);
        String sesionB=new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuthService).token(DemoRole.TAXPAYER,deuda.taxpayerId);
        long eventosPrevios=jdbc.queryForObject("select count(*) from outbox_event where event_type='paymentRegistered'",Long.class);
        jdbc.execute("CREATE FUNCTION delay_test_electronic_attempt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(2); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER delay_test_electronic_attempt BEFORE INSERT ON electronic_payment_attempt FOR EACH ROW EXECUTE FUNCTION delay_test_electronic_attempt()");
        var respuestas=new java.util.concurrent.CopyOnWriteArrayList<MockHttpServletResponse>();
        List<Throwable> fallas;
        try {
            fallas=runTogether(
                ()->{respuestas.add(postElectronic(sesionA,clave,solicitud));return null;},
                ()->{respuestas.add(postElectronic(sesionB,clave,solicitud));return null;});
            String claveConTimeout=UUID.randomUUID().toString();
            ExecutorService cliente=Executors.newSingleThreadExecutor();
            try {
                Future<MockHttpServletResponse> respuestaTardia=cliente.submit(()->postElectronic(sesionA,claveConTimeout,solicitud));
                assertThatThrownBy(()->respuestaTardia.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                MockHttpServletResponse confirmada=respuestaTardia.get(5,TimeUnit.SECONDS);
                MockHttpServletResponse recuperada=postElectronic(sesionB,claveConTimeout,solicitud);
                assertThat(confirmada.getStatus()).isEqualTo(201);
                assertThat(recuperada.getStatus()).isEqualTo(201);
                assertThat(json.readTree(recuperada.getContentAsString()).path("id").asLong())
                    .isEqualTo(json.readTree(confirmada.getContentAsString()).path("id").asLong());
            } finally { cliente.shutdownNow(); }
            String claveConContenidoDistinto=UUID.randomUUID().toString();
            ApiDtos.ElectronicPaymentRequest montoDistinto=new ApiDtos.ElectronicPaymentRequest(deuda.id,PaymentMethod.CASH,new BigDecimal("26"));
            var respuestasDistintas=new java.util.concurrent.CopyOnWriteArrayList<MockHttpServletResponse>();
            List<Throwable> erroresDistintos=runTogether(
                ()->{respuestasDistintas.add(postElectronic(sesionA,claveConContenidoDistinto,solicitud));return null;},
                ()->{respuestasDistintas.add(postElectronic(sesionB,claveConContenidoDistinto,montoDistinto));return null;});
            assertThat(erroresDistintos).containsOnlyNulls();
            assertThat(respuestasDistintas.stream().map(MockHttpServletResponse::getStatus).toList()).containsExactlyInAnyOrder(201,409);
            assertThat(jdbc.queryForObject("select count(*) from electronic_payment_attempt where taxpayer_id=? and idempotency_key=?",Long.class,deuda.taxpayerId,claveConContenidoDistinto)).isOne();
        } finally {
            jdbc.execute("DROP TRIGGER delay_test_electronic_attempt ON electronic_payment_attempt");
            jdbc.execute("DROP FUNCTION delay_test_electronic_attempt()");
        }
        assertThat(fallas).containsOnlyNulls();
        assertThat(respuestas).hasSize(2).allSatisfy(response->assertThat(response.getStatus()).isEqualTo(201));
        var respuestaA=json.readTree(respuestas.get(0).getContentAsString());
        var respuestaB=json.readTree(respuestas.get(1).getContentAsString());
        assertThat(respuestaA.path("id").asLong()).isEqualTo(respuestaB.path("id").asLong());
        assertThat(respuestaA.path("status").asText()).isEqualTo("REJECTED");
        List<ElectronicPaymentAttempt> persistidos=jdbc.query("select id, status from electronic_payment_attempt where taxpayer_id=? and idempotency_key=?",
            (rs,row)->{ElectronicPaymentAttempt attempt=new ElectronicPaymentAttempt();attempt.id=rs.getLong("id");attempt.status=ElectronicPaymentStatus.valueOf(rs.getString("status"));return attempt;},deuda.taxpayerId,clave);
        assertThat(persistidos).singleElement().satisfies(attempt->assertThat(attempt.status).isEqualTo(ElectronicPaymentStatus.REJECTED));
        ApiDtos.ElectronicPaymentRequest distinto=new ApiDtos.ElectronicPaymentRequest(deuda.id,PaymentMethod.CASH,new BigDecimal("26"));
        MockHttpServletResponse conflicto=postElectronic(sesionA,clave,distinto);
        assertThat(conflicto.getStatus()).isEqualTo(409);
        assertThat(json.readTree(conflicto.getContentAsString()).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        MockHttpServletResponse reintento=postElectronic(new DemoAuthSessions(demoUsers,taxpayers,passwordEncoder,demoAuthService).token(DemoRole.TAXPAYER,deuda.taxpayerId),clave,solicitud);
        assertThat(reintento.getStatus()).isEqualTo(201);
        assertThat(json.readTree(reintento.getContentAsString()).path("id").asLong()).isEqualTo(respuestaA.path("id").asLong());
        assertThat(jdbc.queryForObject("select count(*) from electronic_payment_attempt where taxpayer_id=? and idempotency_key=?",Long.class,deuda.taxpayerId,clave)).isOne();
        assertThat(paymentRepository.findByTaxpayerId(deuda.taxpayerId)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type='paymentRegistered'",Long.class)).isEqualTo(eventosPrevios);
        assertThat(debts.findById(deuda.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
    }

    @Test void intentosElectronicosAprobadosConLaMismaClaveCreanUnSoloPago() throws Exception {
        authenticate();
        Debt deuda=debt("ELECTRONICO-APROBADO-IDEMPOTENTE",null);
        ApiDtos.ElectronicPaymentRequest solicitud=new ApiDtos.ElectronicPaymentRequest(deuda.id,PaymentMethod.CARD,new BigDecimal("25"));
        String clave=UUID.randomUUID().toString();
        jdbc.execute("CREATE FUNCTION delay_test_electronic_attempt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(2); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER delay_test_electronic_attempt BEFORE INSERT ON electronic_payment_attempt FOR EACH ROW EXECUTE FUNCTION delay_test_electronic_attempt()");
        var resultados=new java.util.concurrent.CopyOnWriteArrayList<Long>();
        List<Throwable> fallas;
        try {
            fallas=runTogether(
                ()->{authenticateTaxpayer(deuda.taxpayerId);resultados.add(electronicPayments.create(solicitud,clave).id);return null;},
                ()->{authenticateTaxpayer(deuda.taxpayerId);resultados.add(electronicPayments.create(solicitud,clave).id);return null;});
        } finally {
            jdbc.execute("DROP TRIGGER delay_test_electronic_attempt ON electronic_payment_attempt");
            jdbc.execute("DROP FUNCTION delay_test_electronic_attempt()");
        }
        assertThat(fallas).containsOnlyNulls();
        assertThat(resultados).hasSize(2);
        assertThat(resultados.get(0)).isEqualTo(resultados.get(1));
        assertThat(paymentRepository.findByTaxpayerId(deuda.taxpayerId)).hasSize(1);
        Payment pago=paymentRepository.findByTaxpayerId(deuda.taxpayerId).get(0);
        assertThat(allocations.findByPaymentId(pago.id)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from electronic_payment_attempt where taxpayer_id=? and idempotency_key=? and status='APPROVED'",Long.class,deuda.taxpayerId,clave)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type='paymentRegistered' and aggregate_id=?",Long.class,pago.id.toString())).isOne();
        assertThat(auditoria.findByEntityTypeAndEntityIdOrderByOccurredAt("Payment",pago.id.toString())).hasSize(1);
    }

    @Test void pagosElectronicosConcurrentesConClavesDistintasConservanElSaldoEconomico() throws Exception {
        authenticate();
        Debt deuda=debt("ELECTRONICO-SALDO-CONCURRENTE",null);
        ApiDtos.ElectronicPaymentRequest solicitud=new ApiDtos.ElectronicPaymentRequest(deuda.id,PaymentMethod.CARD,new BigDecimal("80"));
        assertThat(runTogether(
            ()->{authenticateTaxpayer(deuda.taxpayerId);electronicPayments.create(solicitud,UUID.randomUUID().toString());return null;},
            ()->{authenticateTaxpayer(deuda.taxpayerId);electronicPayments.create(solicitud,UUID.randomUUID().toString());return null;})).containsOnlyNulls();
        List<Payment> pagos=paymentRepository.findByTaxpayerId(deuda.taxpayerId);
        assertThat(pagos).hasSize(2);
        assertThat(pagos.stream().map(x->x.amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("160.00");
        assertThat(pagos.stream().map(x->x.allocatedAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("100.00");
        assertThat(debts.findById(deuda.id).orElseThrow().outstandingBalance).isZero();
        assertThat(creditRepository.findByTaxpayerId(deuda.taxpayerId).stream().map(x->x.availableAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("60.00");
        assertThat(jdbc.queryForObject("select count(*) from electronic_payment_attempt where taxpayer_id=? and status='APPROVED'",Long.class,deuda.taxpayerId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type='paymentRegistered' and aggregate_id in (?,?)",Long.class,pagos.get(0).id.toString(),pagos.get(1).id.toString())).isEqualTo(2L);
    }

    @Test void claveDePagoSeRevierteConLaTransaccionYEsOpcional() {
        authenticate();
        Debt deuda=debt("IDEMPOTENCIA-ROLLBACK",null);
        String clave=UUID.randomUUID().toString();
        var invalida=new ApiDtos.RegisterPaymentRequest(deuda.taxpayerId,PaymentMethod.CASH,BigDecimal.TEN,List.of(new ApiDtos.AllocationRequest(-1L,BigDecimal.TEN)));
        assertThatThrownBy(()->payments.register(invalida,clave)).isInstanceOf(BusinessException.class);
        assertThat(paymentRepository.findByTaxpayerId(deuda.taxpayerId)).isEmpty();
        var valida=new ApiDtos.RegisterPaymentRequest(deuda.taxpayerId,PaymentMethod.CASH,BigDecimal.TEN,List.of());
        Payment pago=payments.register(valida,clave);
        var equivalente=new ApiDtos.RegisterPaymentRequest(deuda.taxpayerId,PaymentMethod.CASH,new BigDecimal("10.00"),null);
        assertThat(payments.register(equivalente,clave).id).isEqualTo(pago.id);
        assertThat(payments.register(valida,(String)null).id).isNotEqualTo(payments.register(valida,(String)null).id);
        assertThatThrownBy(()->payments.register(valida," ")).isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.status).isEqualTo(400));
        assertThatThrownBy(()->payments.register(valida,"x".repeat(129))).isInstanceOf(BusinessException.class);
        Debt otra=debt("IDEMPOTENCIA-OTRO",null);
        assertThat(payments.register(new ApiDtos.RegisterPaymentRequest(otra.taxpayerId,PaymentMethod.CASH,BigDecimal.TEN,List.of()),clave).id).isNotEqualTo(pago.id);
    }

    @Test void exencionUsaPeriodoFiscalYRegistraTodosLosPasos() {
        authenticate();
        Debt deuda=debt("EXENCION-FISCAL",null);
        LocalDate inicio=YearMonth.now().atDay(1),fin=YearMonth.now().atEndOfMonth();
        var solicitud=exenciones.create(new ApiDtos.CreateExemptionRequest(deuda.taxpayerId,deuda.taxConceptId,"Validación fiscal",new BigDecimal("100"),inicio,fin));
        exenciones.start(solicitud.id);
        exenciones.requestDocumentation(solicitud.id,"Presentar constancia");
        exenciones.submitDocumentation(solicitud.id,new ApiDtos.SubmitDocumentationRequest("DOC-QA","CONSTANCIA","constancia.pdf"));
        exenciones.submit(solicitud.id);
        assertThat(auditoria.findByEntityTypeAndEntityIdOrderByOccurredAt("ExemptionRequest",solicitud.id.toString()))
            .extracting(x->x.action).containsExactly("EXEMPTION_REQUESTED","EXEMPTION_REVIEW_STARTED",
                "EXEMPTION_DOCUMENTATION_REQUESTED","EXEMPTION_DOCUMENTATION_SUBMITTED","EXEMPTION_SUBMITTED_FOR_RESOLUTION");
        TestActorSupport.asApprover(() -> exenciones.approve(solicitud.id));
        assertThat(liquidations.preview(new ApiDtos.LiquidationRequest(deuda.taxpayerId,deuda.taxConceptId,YearMonth.now().toString(),BigDecimal.ZERO,fin.plusMonths(3))).finalAmount()).isZero();
        var futura=new ApiDtos.LiquidationRequest(deuda.taxpayerId,deuda.taxConceptId,YearMonth.now().plusYears(2).toString(),BigDecimal.ZERO,fin.plusYears(2));
        assertThat(liquidations.create(futura).finalAmount).isEqualByComparingTo("100");
        assertThat(liquidations.preview(new ApiDtos.LiquidationRequest(deuda.taxpayerId,deuda.taxConceptId,YearMonth.now().minusMonths(1).toString(),BigDecimal.ZERO,fin)).finalAmount()).isEqualByComparingTo("100");
        assertThatThrownBy(()->liquidations.preview(new ApiDtos.LiquidationRequest(deuda.taxpayerId,deuda.taxConceptId,"2026-13",BigDecimal.ZERO,fin)))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.status).isEqualTo(400));
    }
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/migration-postgresql,classpath:db/migration-demo");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired LiquidationRepository liquidationRecords;
    @Autowired PlanWorkflowService workflow;
    @Autowired PaymentService payments;
    @Autowired ElectronicPaymentService electronicPayments;
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
    @Autowired ConfirmedIntegrationOutbox confirmedOutbox;
    @Autowired PreliminaryM4Consumer m4;
    @Autowired CoreM4Consumer coreM4;
    @Autowired IntegrationReprocessService integrationReprocess;
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
    @Autowired DemoAuthController demoAuth;
    @Autowired DemoAuthService demoAuthService;
    @Autowired DemoLoginAttemptLimiter demoLoginAttempts;
    @Autowired DemoUserRepository demoUsers;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired MockMvc mvc;
    @Autowired ApiController apiController;
    @Autowired IdempotencyService idempotencyService;
    @Autowired AdjustmentService adjustmentService;
    @Autowired ConfirmedM8Consumer coreM8;
    @Autowired SocialBenefitRepository socialBenefits;
    @Autowired SocialBenefitEventHistoryRepository socialBenefitHistory;
    @Autowired LateChargeRuleRepository lateChargeRules;
    @Autowired LateChargeService lateCharges;
    @Autowired DueDateService dueDates;
    @Autowired DueDateProcessingRepository dueDateRuns;
    @Autowired PaymentPlanConfigurationRepository planConfigurations;
    @Autowired TaxConfigurationRepository taxConfigurations;
    @Autowired ReconciliationService reconciliations;
    @Autowired LiquidationRunService bulkRuns;

    @AfterEach void clearSecurity(){SecurityContextHolder.clearContext();}

    @Test void demoLoginRateLimitAllowsFiveFailuresThenReturnsRetryAfter() throws Exception {
        String username="rate-limit-"+UUID.randomUUID();
        String request="{\"username\":\""+username+"\",\"password\":\"incorrecta\"}";
        for(int attempt=0;attempt<5;attempt++)
            mvc.perform(post("/api/v1/dev-auth/login").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(post("/api/v1/dev-auth/login").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After","900"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("DEMO_LOGIN_RATE_LIMITED"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Demasiados intentos. Reintentá en 15 minutos"));
    }

    @Test void etagProtectsAllThreePatchResourcesAndRejectsStaleWrites() {
        authenticate();
        TaxConcept concept=concept("ETAG_"+UUID.randomUUID(),TaxConceptType.FEE,"M5");
        String conceptTag=apiController.concept(concept.id).getHeaders().getETag();
        var conceptUpdated=apiController.updateConcept(concept.id,new ApiDtos.UpdateTaxConceptRequest("ETag updated",null,true),conceptTag);
        assertThat(conceptUpdated.getStatusCode().value()).isEqualTo(200);
        assertThat(conceptUpdated.getHeaders().getETag()).isNotEqualTo(conceptTag);
        assertThatThrownBy(()->apiController.updateConcept(concept.id,new ApiDtos.UpdateTaxConceptRequest("stale",null,false),conceptTag))
            .isInstanceOf(BusinessException.class).satisfies(error->assertThat(((BusinessException)error).status).isEqualTo(412));
        assertThatThrownBy(()->apiController.updateConcept(concept.id,new ApiDtos.UpdateTaxConceptRequest("wildcard bypass",null,false),"\"stale\", *"))
            .isInstanceOf(BusinessException.class).satisfies(error->assertThat(((BusinessException)error).status).isEqualTo(412));
        assertThat(concepts.findById(concept.id).orElseThrow().name).isEqualTo("ETag updated");

        TaxConfiguration tax=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.PERCENTAGE,new BigDecimal("5"),null,null,null,true,true,LocalDate.now().minusDays(1),null));
        String taxTag=apiController.configuration(tax.id).getHeaders().getETag();
        var taxUpdated=apiController.updateConfiguration(tax.id,new ApiDtos.UpdateTaxConfigurationRequest(CalculationType.PERCENTAGE,new BigDecimal("7"),null,null,null,null,null,null,null),taxTag);
        assertThat(taxUpdated.getHeaders().getETag()).isNotEqualTo(taxTag);
        assertThatThrownBy(()->apiController.updateConfiguration(tax.id,new ApiDtos.UpdateTaxConfigurationRequest(CalculationType.PERCENTAGE,new BigDecimal("9"),null,null,null,null,null,null,null),taxTag))
            .isInstanceOf(BusinessException.class).satisfies(error->assertThat(((BusinessException)error).status).isEqualTo(412));
        assertThat(catalog.updateConfiguration(tax.id,new ApiDtos.UpdateTaxConfigurationRequest(null,null,null,null,null,null,null,null,null)).rate).isEqualByComparingTo("7");

        PaymentPlanConfiguration plan=workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(1,12,new BigDecimal("10"),new BigDecimal("5"),0,2,true,true,1,LocalDate.now().minusDays(1),null,true));
        String planTag=apiController.planConfiguration(plan.id).getHeaders().getETag();
        var planUpdated=apiController.updatePlanConfiguration(plan.id,new ApiDtos.UpdatePaymentPlanConfigurationRequest(null,null,null,new BigDecimal("6"),null,null,null,null,null,null,null,null),planTag);
        assertThat(planUpdated.getHeaders().getETag()).isNotEqualTo(planTag);
        assertThatThrownBy(()->apiController.updatePlanConfiguration(plan.id,new ApiDtos.UpdatePaymentPlanConfigurationRequest(null,null,null,new BigDecimal("8"),null,null,null,null,null,null,null,null),planTag))
            .isInstanceOf(BusinessException.class).satisfies(error->assertThat(((BusinessException)error).status).isEqualTo(412));
        assertThat(planConfigurations.findFirstByOrderByVersionDesc().orElseThrow().interestRate).isEqualByComparingTo("6");
    }

    @Test void etagHttpContractSupportsFreshStaleMultiTagWildcardAndMissingHeaders() throws Exception {
        authenticate();
        var rentas=new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("etag-http-"+UUID.randomUUID(),null),null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS")));
        TaxConcept concept=concept("ETAG_HTTP_"+UUID.randomUUID(),TaxConceptType.FEE,"M5");
        String conceptPath="/api/v1/tax-concepts/"+concept.id;
        String conceptTag=mvc.perform(get(conceptPath).with(authentication(rentas)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn().getResponse().getHeader("ETag");
        assertThat(conceptTag).isNotBlank();
        var firstConceptBody=json.writeValueAsBytes(new ApiDtos.UpdateTaxConceptRequest("etag first",null,true));
        var firstConcept=mvc.perform(patch(conceptPath).with(authentication(rentas)).header("If-Match",conceptTag)
                .contentType(MediaType.APPLICATION_JSON).content(firstConceptBody))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        String currentConceptTag=firstConcept.getHeader("ETag");
        assertThat(currentConceptTag).isNotEqualTo(conceptTag);
        mvc.perform(patch(conceptPath).with(authentication(rentas)).header("If-Match",conceptTag)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new ApiDtos.UpdateTaxConceptRequest("stale",null,false))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPreconditionFailed())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("PRECONDITION_FAILED"));
        assertThat(concepts.findById(concept.id).orElseThrow().name).isEqualTo("etag first");
        var multiTagUpdate=mvc.perform(patch(conceptPath).with(authentication(rentas)).header("If-Match","\"obsolete\", "+currentConceptTag)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new ApiDtos.UpdateTaxConceptRequest("etag list",null,true))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        var wildcardUpdate=mvc.perform(patch(conceptPath).with(authentication(rentas)).header("If-Match","*")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new ApiDtos.UpdateTaxConceptRequest("etag wildcard",null,true))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        assertThat(multiTagUpdate.getHeader("ETag")).isNotEqualTo(currentConceptTag);
        assertThat(wildcardUpdate.getHeader("ETag")).isNotEqualTo(multiTagUpdate.getHeader("ETag"));
        mvc.perform(patch(conceptPath).with(authentication(rentas))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new ApiDtos.UpdateTaxConceptRequest("etag legacy",null,true))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(concepts.findById(concept.id).orElseThrow().name).isEqualTo("etag legacy");

        authenticate();
        TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.PERCENTAGE,
            new BigDecimal("5"),null,null,null,true,true,LocalDate.now().minusDays(1),null));
        String configurationPath="/api/v1/tax-configurations/"+configuration.id;
        String configurationTag=mvc.perform(get(configurationPath).with(authentication(rentas)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getHeader("ETag");
        var rateUpdate=json.writeValueAsBytes(new ApiDtos.UpdateTaxConfigurationRequest(CalculationType.PERCENTAGE,new BigDecimal("7"),null,null,null,null,null,null,null));
        mvc.perform(patch(configurationPath).with(authentication(rentas)).header("If-Match","\"obsolete\"")
                .contentType(MediaType.APPLICATION_JSON).content(rateUpdate))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPreconditionFailed());
        assertThat(taxConfigurations.findById(configuration.id).orElseThrow().rate).isEqualByComparingTo("5");
        mvc.perform(patch(configurationPath).with(authentication(rentas)).header("If-Match",configurationTag)
                .contentType(MediaType.APPLICATION_JSON).content(rateUpdate))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        authenticate();
        PaymentPlanConfiguration plan=workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(1,12,new BigDecimal("10"),
            new BigDecimal("5"),0,2,true,true,1,LocalDate.now().minusDays(1),null,false));
        String planPath="/api/v1/payment-plan-configurations/"+plan.id;
        String planTag=mvc.perform(get(planPath).with(authentication(rentas)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getHeader("ETag");
        var planUpdate=json.writeValueAsBytes(new ApiDtos.UpdatePaymentPlanConfigurationRequest(null,null,null,new BigDecimal("6"),null,null,null,null,null,null,null,null));
        mvc.perform(patch(planPath).with(authentication(rentas)).header("If-Match","\"obsolete\"")
                .contentType(MediaType.APPLICATION_JSON).content(planUpdate))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPreconditionFailed());
        assertThat(planConfigurations.findById(plan.id).orElseThrow().interestRate).isEqualByComparingTo("5");
        var updatedPlan=mvc.perform(patch(planPath).with(authentication(rentas)).header("If-Match",planTag)
                .contentType(MediaType.APPLICATION_JSON).content(planUpdate))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        assertThat(updatedPlan.getHeader("ETag")).isNotEqualTo(planTag);
        assertThat(planConfigurations.findFirstByOrderByVersionDesc().orElseThrow().interestRate).isEqualByComparingTo("6");
    }

    @Test void concurrentHttpWritersUsingOneEtagAllowOnlyOneUpdate() throws Exception {
        authenticate();
        TaxConcept concept=concept("ETAG_RACE_"+UUID.randomUUID(),TaxConceptType.FEE,"M5");
        String path="/api/v1/tax-concepts/"+concept.id;
        var rentas=new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("etag-race-"+UUID.randomUUID(),null),null,
            List.of(new SimpleGrantedAuthority("ROLE_RENTAS")));
        String tag=mvc.perform(get(path).with(authentication(rentas))).andReturn().getResponse().getHeader("ETag");
        assertThat(tag).isNotBlank();
        var first=json.writeValueAsBytes(new ApiDtos.UpdateTaxConceptRequest("race first",null,true));
        var second=json.writeValueAsBytes(new ApiDtos.UpdateTaxConceptRequest("race second",null,true));
        List<Integer> statuses=new java.util.concurrent.CopyOnWriteArrayList<>();
        assertThat(runTogether(
            ()->{statuses.add(mvc.perform(patch(path).with(authentication(rentas)).header("If-Match",tag).contentType(MediaType.APPLICATION_JSON).content(first)).andReturn().getResponse().getStatus());return null;},
            ()->{statuses.add(mvc.perform(patch(path).with(authentication(rentas)).header("If-Match",tag).contentType(MediaType.APPLICATION_JSON).content(second)).andReturn().getResponse().getStatus());return null;})).containsOnlyNulls();
        assertThat(statuses).containsExactlyInAnyOrder(200,412);
        assertThat(List.of("race first","race second")).contains(concepts.findById(concept.id).orElseThrow().name);
    }

    @Test void openApiDocumentsOptionalIfMatchAndEtagResponsesForVersionedResources() throws Exception {
        var document=json.readTree(mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
        for(String pathName:List.of("/api/v1/tax-concepts/{id}","/api/v1/tax-configurations/{id}","/api/v1/payment-plan-configurations/{id}")) {
            var path=document.path("paths").path(pathName);
            var patchOperation=path.path("patch");
            var parameters=patchOperation.path("parameters");
            boolean foundOptionalIfMatch=false;
            for(var parameter:parameters) {
                if("If-Match".equals(parameter.path("name").asText())) {
                    foundOptionalIfMatch=true;
                    assertThat(parameter.path("required").asBoolean()).isFalse();
                    assertThat(parameter.path("description").asText()).contains("compatibilidad sin precondición");
                }
            }
            assertThat(foundOptionalIfMatch).as("If-Match documentado en %s",pathName).isTrue();
            assertThat(patchOperation.path("responses").path("412").path("$ref").asText())
                .isEqualTo("#/components/responses/ApiError");
            assertThat(path.path("get").path("responses").path("200").path("headers").has("ETag")).isTrue();
            assertThat(patchOperation.path("responses").path("200").path("headers").has("ETag")).isTrue();
        }
    }

    @Test void openApiDocumentsIdempotencyForEveryMutatingPostAndExcludesOnlyPreviewsAndSimulations() throws Exception {
        var document=json.readTree(mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
        var excluded=java.util.Set.of(
            "/api/v1/liquidations/preview",
            "/api/v1/electronic-payments/preview",
            "/api/v1/debts/{id}/late-charge-preview",
            "/api/v1/payment-plans/simulations",
            "/api/v1/payment-plans/{planId}/refinancing/simulations");
        int postCount=0,covered=0;
        var paths=document.path("paths");
        var names=paths.fieldNames();
        while(names.hasNext()) {
            String path=names.next();
            var operation=paths.path(path).path("post");
            if(operation.isMissingNode()) continue;
            postCount++;
            boolean hasKey=false;
            for(var parameter:operation.path("parameters")) {
                if("Idempotency-Key".equalsIgnoreCase(parameter.path("name").asText())) {
                    assertThat(parameter.path("in").asText()).isEqualTo("header");
                    assertThat(parameter.path("required").asBoolean()).isFalse();
                    hasKey=true;
                }
            }
            boolean excludedFromIdempotency=excluded.contains(path)||path.startsWith("/api/v1/dev-auth/");
            assertThat(hasKey).as("Idempotency-Key en POST %s",path).isEqualTo(!excludedFromIdempotency);
            if(hasKey) covered++;
        }
        assertThat(paths.path("/api/v1/tickets/{id}/return").path("post").isMissingNode()).isFalse();
        assertThat(postCount).isEqualTo(76);
        assertThat(covered).isEqualTo(67);
    }

    @Test void taxConceptCreationEndpointReplaysConcurrentRequestAndConflictsOnDifferentBody() throws Exception {
        String code="HTTP_IDEMP_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        String key="tax-concept-"+UUID.randomUUID();
        String actor="tax-concept-http-"+UUID.randomUUID();
        var auth=new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(actor,null),null,List.of(new SimpleGrantedAuthority("ROLE_RENTAS")));
        var request=new ApiDtos.CreateTaxConceptRequest(code,"Concepto idempotente",null,TaxConceptType.FEE,"M5");
        String body=json.writeValueAsString(request);
        var responses=new java.util.concurrent.CopyOnWriteArrayList<String>();
        assertThat(runTogether(
            ()->{responses.add(mvc.perform(post("/api/v1/tax-concepts").with(authentication(auth)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString());return null;},
            ()->{responses.add(mvc.perform(post("/api/v1/tax-concepts").with(authentication(auth)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString());return null;})).containsOnlyNulls();
        assertThat(responses).hasSize(2);
        long id=json.readTree(responses.get(0)).path("id").asLong();
        assertThat(json.readTree(responses.get(1)).path("id").asLong()).isEqualTo(id);
        assertThat(concepts.findByCode(code)).isPresent();
        var changed=new ApiDtos.CreateTaxConceptRequest(code,"Otro nombre",null,TaxConceptType.FEE,"M5");
        mvc.perform(post("/api/v1/tax-concepts").with(authentication(auth)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(changed)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(concepts.findByCode(code)).hasValueSatisfying(saved->assertThat(saved.id).isEqualTo(id));
    }

    @Test void configurationTransitionEndpointReplaysCompletedAction() throws Exception {
        authenticate();
        TaxConcept concept=concept("HTTP_CONFIG_ACTION_"+UUID.randomUUID(),TaxConceptType.FEE,"M5");
        TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            concept.id,CalculationType.FIXED,null,new BigDecimal("25"),null,null,true,true,LocalDate.now().minusDays(1),null));
        String key="configuration-submit-"+UUID.randomUUID(),actor="configuration-submit-http-"+UUID.randomUUID();
        var auth=new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(actor,null),null,List.of(new SimpleGrantedAuthority("ROLE_RENTAS")));
        var first=mvc.perform(post("/api/v1/tax-configurations/"+configuration.id+"/submit").with(authentication(auth)).header("Idempotency-Key",key))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        var replay=mvc.perform(post("/api/v1/tax-configurations/"+configuration.id+"/submit").with(authentication(auth)).header("Idempotency-Key",key))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(replay).path("id").asLong()).isEqualTo(json.readTree(first).path("id").asLong());
        assertThat(json.readTree(replay).path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where actor_id=? and operation=? and idempotency_key=?",Long.class,
            actor,"POST:/tax-configurations/{id}/submit:"+configuration.id,key)).isOne();
    }

    @Test void creditApplicationEndpointReplaysEconomicEffectAndRejectsChangedAmount() throws Exception {
        authenticate();
        Debt source=debt("HTTP-CREDIT-SOURCE-"+UUID.randomUUID(),null);
        Debt target=debt("HTTP-CREDIT-TARGET-"+UUID.randomUUID(),source.taxpayerId);
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(source.taxpayerId,PaymentMethod.CASH,new BigDecimal("120"),List.of(new ApiDtos.AllocationRequest(source.id,new BigDecimal("100")))));
        CreditBalance credit=creditRepository.findBySourcePaymentId(payment.id).orElseThrow();
        String key="credit-apply-"+UUID.randomUUID(),actor="credit-apply-http-"+UUID.randomUUID();
        var auth=new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(actor,null),null,List.of(new SimpleGrantedAuthority("ROLE_RENTAS")));
        var request=new ApiDtos.ApplyCreditBalanceRequest(target.id,new BigDecimal("10"));
        String body=json.writeValueAsString(request);
        var first=mvc.perform(post("/api/v1/credit-balances/"+credit.id+"/apply").with(authentication(auth)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        var replay=mvc.perform(post("/api/v1/credit-balances/"+credit.id+"/apply").with(authentication(auth)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(replay).path("id").asLong()).isEqualTo(json.readTree(first).path("id").asLong());
        assertThat(creditApplications.findAll().stream().filter(application->credit.id.equals(application.creditBalanceId))).hasSize(1);
        assertThat(creditRepository.findById(credit.id).orElseThrow().availableAmount).isEqualByComparingTo("10.00");
        var changed=new ApiDtos.ApplyCreditBalanceRequest(target.id,new BigDecimal("5"));
        mvc.perform(post("/api/v1/credit-balances/"+credit.id+"/apply").with(authentication(auth)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(changed)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(creditApplications.findAll().stream().filter(application->credit.id.equals(application.creditBalanceId))).hasSize(1);
    }

    @Test void idempotencyForEconomicCreationIsTransactionalAndSerializesConcurrentRetries() throws Exception {
        authenticate();
        Debt debt=debt("POST-IDEMPOTENCY-"+UUID.randomUUID(),null);
        var request=new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.DISCOUNT,new BigDecimal("10"),"Ajuste repetible");
        String key="adjustment-"+UUID.randomUUID();
        var responses=new java.util.concurrent.CopyOnWriteArrayList<ApiDtos.AdjustmentResponse>();
        assertThat(runTogether(
            ()->{responses.add(idempotencyService.execute("POST:/adjustments",key,request,ApiDtos.AdjustmentResponse.class,()->ApiResponses.of(adjustmentService.create(request))));return null;},
            ()->{responses.add(idempotencyService.execute("POST:/adjustments",key,request,ApiDtos.AdjustmentResponse.class,()->ApiResponses.of(adjustmentService.create(request))));return null;})).containsOnlyNulls();
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo(responses.get(1).id());
        assertThat(jdbc.queryForObject("select count(*) from adjustment_request where debt_id=?",Long.class,debt.id)).isEqualTo(1);
        assertThat(outbox.findAll()).noneMatch(event->event.eventType.equals("debtAdjusted")&&event.aggregateId.equals(debt.id.toString()));
        var conflicting=new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.DISCOUNT,new BigDecimal("11"),"Distinto");
        assertThatThrownBy(()->idempotencyService.execute("POST:/adjustments",key,conflicting,ApiDtos.AdjustmentResponse.class,()->ApiResponses.of(adjustmentService.create(conflicting))))
            .isInstanceOf(BusinessException.class).satisfies(error->{assertThat(((BusinessException)error).status).isEqualTo(409);assertThat(((BusinessException)error).code).isEqualTo("IDEMPOTENCY_KEY_REUSED");});
        assertThat(jdbc.queryForObject("select count(*) from adjustment_request where debt_id=?",Long.class,debt.id)).isEqualTo(1);
    }

    @Test void adjustmentEndpointReplaysEquivalentResponseAndRejectsChangedBody() throws Exception {
        authenticate();
        Debt debt=debt("HTTP-IDEMPOTENCY-"+UUID.randomUUID(),null);
        String actor="adjustment-http-"+UUID.randomUUID();
        var auth=new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(actor,null),null,List.of(new SimpleGrantedAuthority("ROLE_RENTAS")));
        var request=new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.DISCOUNT,new BigDecimal("10"),"HTTP idempotency");
        String key="http-adjustment-"+UUID.randomUUID();
        String body=json.writeValueAsString(request);
        var first=mvc.perform(post("/api/v1/adjustments").with(authentication(auth)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn().getResponse().getContentAsString();
        var retry=mvc.perform(post("/api/v1/adjustments").with(authentication(auth)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn().getResponse().getContentAsString();
        var firstJson=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(first);
        var retryJson=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(retry);
        assertThat(OffsetDateTime.parse(retryJson.get("requestedAt").asText()).toInstant())
            .isEqualTo(OffsetDateTime.parse(firstJson.get("requestedAt").asText()).toInstant());
        firstJson.remove("requestedAt");
        retryJson.remove("requestedAt");
        assertThat(retryJson).isEqualTo(firstJson);
        assertThat(jdbc.queryForObject("select count(*) from adjustment_request where debt_id=?",Long.class,debt.id)).isEqualTo(1);
        var changed=new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.DISCOUNT,new BigDecimal("11"),"Otro contenido");
        mvc.perform(post("/api/v1/adjustments").with(authentication(auth)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(changed)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from adjustment_request where debt_id=?",Long.class,debt.id)).isEqualTo(1);
    }

    @Test void failedIdempotentActionRollsBackReservationAndCanBeRetried() {
        authenticate();String key="rollback-"+UUID.randomUUID();String operation="POST:/test-rollback";
        assertThatThrownBy(()->idempotencyService.execute(operation,key,Map.of("value",1),String.class,()->{throw new BusinessException("EXPECTED_FAILURE","expected",422);})).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where actor_id=? and operation=? and idempotency_key=?",Long.class,"postgres-it",operation,key)).isZero();
        assertThat(idempotencyService.execute(operation,key,Map.of("value",1),String.class,()->"accepted")).isEqualTo("accepted");
    }

    @Test void concurrentPlanRequestRetryCreatesOneRequestAndOneOutboxEvent() throws Exception {
        authenticate();
        Debt debt=debt("PLAN-REQUEST-IDEMPOTENCY-"+UUID.randomUUID(),null);
        TaxpayerReference owner=taxpayers.findById(debt.taxpayerId).orElseThrow();owner.canonicalCitizenId=UUID.randomUUID();taxpayers.saveAndFlush(owner);
        workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(1,12,new BigDecimal("10"),new BigDecimal("5"),0,2,true,true,1,LocalDate.now().minusDays(1),null,true));
        var request=new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),3);
        String key="plan-request-"+UUID.randomUUID();var responses=new java.util.concurrent.CopyOnWriteArrayList<ApiDtos.PaymentPlanRequestResponse>();
        assertThat(runTogether(
            ()->{responses.add(idempotencyService.execute("POST:/payment-plan-requests",key,request,ApiDtos.PaymentPlanRequestResponse.class,()->ApiResponses.of(workflow.request(request))));return null;},
            ()->{responses.add(idempotencyService.execute("POST:/payment-plan-requests",key,request,ApiDtos.PaymentPlanRequestResponse.class,()->ApiResponses.of(workflow.request(request))));return null;})).containsOnlyNulls();
        assertThat(responses).hasSize(2).extracting(ApiDtos.PaymentPlanRequestResponse::id).containsOnly(responses.get(0).id());
        assertThat(jdbc.queryForObject("select count(*) from payment_plan_request where taxpayer_id=?",Long.class,debt.taxpayerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type='paymentPlanRequested' and aggregate_type='PaymentPlanRequest' and aggregate_id=?",Long.class,responses.get(0).id().toString())).isEqualTo(1);
    }

    @Test void successfulDemoLoginResetsTheAttemptWindow() {
        authenticate();
        String username="rate-reset-"+UUID.randomUUID();
        demoAuth.create(new DemoAuthController.CreateUserRequest(username,"clave-segura","Rate reset",DemoRole.RENTAS,null));
        var request=new DemoAuthController.LoginRequest(username,"clave-segura");
        assertThatThrownBy(()->demoAuthService.login(new DemoAuthController.LoginRequest(username,"incorrecta"),"127.0.0.1"))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("INVALID_CREDENTIALS"));
        assertThat(demoAuthService.login(request,"127.0.0.1").user().username()).isEqualTo(username);
        for(int attempt=0;attempt<5;attempt++)
            assertThatThrownBy(()->demoAuthService.login(new DemoAuthController.LoginRequest(username,"incorrecta"),"127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code","INVALID_CREDENTIALS");
        assertThatThrownBy(()->demoAuthService.login(new DemoAuthController.LoginRequest(username,"incorrecta"),"127.0.0.1"))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("code","DEMO_LOGIN_RATE_LIMITED");
    }

    @Test void validDemoLoginCannotBypassAnExhaustedAttemptWindow() {
        authenticate();
        String username="rate-lock-"+UUID.randomUUID();
        String remoteAddress="198.51.100.71";
        demoAuth.create(new DemoAuthController.CreateUserRequest(username,"clave-segura","Rate lock",DemoRole.RENTAS,null));
        var invalid=new DemoAuthController.LoginRequest(username,"incorrecta");
        for(int attempt=0;attempt<5;attempt++)
            assertThatThrownBy(()->demoAuthService.login(invalid,remoteAddress))
                .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("INVALID_CREDENTIALS"));

        assertThatThrownBy(()->demoAuthService.login(new DemoAuthController.LoginRequest(username,"clave-segura"),remoteAddress))
            .isInstanceOfSatisfying(BusinessException.class,ex->{assertThat(ex.status).isEqualTo(429);assertThat(ex.code).isEqualTo("DEMO_LOGIN_RATE_LIMITED");});
        String key=DemoAuthService.hashToken("login:"+username+":"+remoteAddress);
        assertThat(jdbc.queryForObject("select attempts from demo_login_attempt where attempt_key_hash=?",Integer.class,key)).isEqualTo(5);
        jdbc.update("UPDATE demo_login_attempt SET window_started_at=CURRENT_TIMESTAMP - INTERVAL '16 minutes' WHERE attempt_key_hash=?",key);
        assertThat(demoAuthService.login(new DemoAuthController.LoginRequest(username,"clave-segura"),remoteAddress).user().username()).isEqualTo(username);
        demoLoginAttempts.reset(key);
    }

    @Test void demoLoginAttemptWindowsAreIsolatedByUsername() {
        authenticate();
        String remoteAddress="198.51.100.72";
        String first="rate-first-"+UUID.randomUUID(),second="rate-second-"+UUID.randomUUID();
        demoAuth.create(new DemoAuthController.CreateUserRequest(first,"clave-segura","First",DemoRole.RENTAS,null));
        demoAuth.create(new DemoAuthController.CreateUserRequest(second,"clave-segura","Second",DemoRole.RENTAS,null));
        for(int attempt=0;attempt<5;attempt++)
            assertThatThrownBy(()->demoAuthService.login(new DemoAuthController.LoginRequest(first,"incorrecta"),remoteAddress))
                .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("INVALID_CREDENTIALS"));

        assertThat(demoAuthService.login(new DemoAuthController.LoginRequest(second,"clave-segura"),remoteAddress).user().username()).isEqualTo(second);
        assertThatThrownBy(()->demoAuthService.login(new DemoAuthController.LoginRequest(first,"clave-segura"),remoteAddress))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("DEMO_LOGIN_RATE_LIMITED"));
        demoLoginAttempts.reset(DemoAuthService.hashToken("login:"+first+":"+remoteAddress));
        demoLoginAttempts.reset(DemoAuthService.hashToken("login:"+second+":"+remoteAddress));
    }

    @Test void existingAndUnknownDemoUsersReceiveTheSameCredentialError() {
        authenticate();
        String existing="rate-existing-"+UUID.randomUUID();
        demoAuth.create(new DemoAuthController.CreateUserRequest(existing,"clave-segura","Existing",DemoRole.RENTAS,null));
        for(String username:List.of(existing,"rate-unknown-"+UUID.randomUUID()))
            assertThatThrownBy(()->demoAuthService.login(new DemoAuthController.LoginRequest(username,"incorrecta"),"198.51.100.75"))
                .isInstanceOfSatisfying(BusinessException.class,ex->{assertThat(ex.status).isEqualTo(401);assertThat(ex.code).isEqualTo("INVALID_CREDENTIALS");});
    }

    @Test void bootstrapSecretCannotBypassAnExhaustedAttemptWindow() {
        String remoteAddress="198.51.100.73";
        var request=new DemoAuthController.BootstrapRequest("bootstrap-"+UUID.randomUUID(),"clave-segura","Bootstrap");
        for(int attempt=0;attempt<5;attempt++)
            assertThatThrownBy(()->demoAuthService.bootstrap("incorrecto",request,remoteAddress))
                .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.code).isEqualTo("INVALID_BOOTSTRAP_SECRET"));

        assertThatThrownBy(()->demoAuthService.bootstrap("test-bootstrap-secret",request,remoteAddress))
            .isInstanceOfSatisfying(BusinessException.class,ex->{assertThat(ex.status).isEqualTo(429);assertThat(ex.code).isEqualTo("DEMO_LOGIN_RATE_LIMITED");});
        assertThat(jdbc.queryForObject("select attempts from demo_login_attempt where attempt_key_hash=?",Integer.class,
            DemoAuthService.hashToken("bootstrap:"+remoteAddress))).isEqualTo(5);
        demoLoginAttempts.reset(DemoAuthService.hashToken("bootstrap:"+remoteAddress));
    }

    @Test void concurrentDemoLoginRequestsCannotExceedFiveAdmissions() throws Exception {
        String username="rate-concurrent-"+UUID.randomUUID();
        String remoteAddress="198.51.100.74";
        var invalid=new DemoAuthController.LoginRequest(username,"incorrecta");
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> attempts=java.util.stream.IntStream.range(0,24)
                .<Callable<String>>mapToObj(ignored->()->{
                    try {demoAuthService.login(invalid,remoteAddress);return "UNEXPECTED_SUCCESS";}
                    catch(BusinessException ex) {return ex.code;}
                }).toList();
            List<String> outcomes=pool.invokeAll(attempts).stream().map(future->{try{return future.get();}catch(Exception ex){throw new RuntimeException(ex);}}).toList();
            assertThat(outcomes.stream().filter("INVALID_CREDENTIALS"::equals).count()).isEqualTo(5);
            assertThat(outcomes.stream().filter("DEMO_LOGIN_RATE_LIMITED"::equals).count()).isEqualTo(19);
            assertThat(outcomes).doesNotContain("UNEXPECTED_SUCCESS");
        } finally {pool.shutdownNow();}
        demoLoginAttempts.reset(DemoAuthService.hashToken("login:"+username+":"+remoteAddress));
    }

    @Test void demoLoginRateLimitIsAtomicUnderConcurrentFailures() throws Exception {
        String key=DemoAuthService.hashToken("concurrent-rate-limit:"+UUID.randomUUID());
        ExecutorService pool=Executors.newFixedThreadPool(12);
        try {
            List<Callable<Boolean>> attempts=java.util.stream.IntStream.range(0,24)
                .<Callable<Boolean>>mapToObj(ignored->()->demoLoginAttempts.allowAttempt(key)).toList();
            long allowed=pool.invokeAll(attempts).stream().filter(future->{try{return future.get();}catch(Exception ex){throw new RuntimeException(ex);}}).count();
            assertThat(allowed).isEqualTo(5);
        } finally {pool.shutdownNow();}
        demoLoginAttempts.reset(key);
        assertThat(demoLoginAttempts.allowAttempt(key)).isTrue();
    }

    @Test void demoLoginRateLimitExpiresAfterFifteenMinutesAndIsSharedAcrossInstances() {
        String key=DemoAuthService.hashToken("expiring-rate-limit:"+UUID.randomUUID());
        DemoLoginAttemptLimiter secondInstance=new PostgreSqlDemoLoginAttemptLimiter(jdbc);
        assertThat(demoLoginAttempts.allowAttempt(key)).isTrue();
        assertThat(secondInstance.allowAttempt(key)).isTrue();
        jdbc.update("UPDATE demo_login_attempt SET window_started_at=CURRENT_TIMESTAMP - INTERVAL '16 minutes' WHERE attempt_key_hash=?",key);
        for(int attempt=0;attempt<5;attempt++) assertThat(secondInstance.allowAttempt(key)).isTrue();
        assertThat(demoLoginAttempts.allowAttempt(key)).isFalse();
        secondInstance.reset(key);
    }

    @Test void m8OfficialEventsPersistOnPostgreSqlWithoutAssumingCitizenIdentity(){
        UUID benefitId=UUID.randomUUID(),citizenId=UUID.randomUUID(),applicationId=UUID.randomUUID(),programId=UUID.randomUUID();
        OffsetDateTime initial=OffsetDateTime.parse("2026-10-01T12:00:00-03:00");
        var approved=m8Event(benefitId,citizenId,applicationId,programId,"APPROVED",initial);
        coreM8.consume(approved);
        coreM8.consume(approved);
        coreM8.consume(m8Event(benefitId,citizenId,applicationId,programId,"SUSPENDED",initial.plusDays(2)));
        coreM8.consume(m8Event(benefitId,citizenId,applicationId,programId,"FINALIZED",initial.plusDays(1)));
        SocialBenefitReference stored=socialBenefits.findByExternalBenefitId(benefitId.toString()).orElseThrow();
        assertThat(stored.externalCitizenId).isEqualTo(citizenId.toString());
        assertThat(stored.externalApplicationId).isEqualTo(applicationId.toString());
        assertThat(stored.externalProgramId).isEqualTo(programId.toString());
        assertThat(stored.externalStatus).isEqualTo("SUSPENDED");
        assertThat(stored.validFrom).isEqualTo(LocalDate.of(2026,10,1));
        assertThat(stored.taxpayerId).isNull();
        assertThat(stored.resolutionStatus).isEqualTo("PENDING_CITIZEN");
        assertThat(socialBenefitHistory.findByExternalBenefitIdOrderBySourceUpdatedAtAsc(benefitId.toString()))
            .extracting(x->x.outcome).containsExactly("APPLIED","STALE","APPLIED");
    }

    private ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent m8Event(UUID benefitId,UUID citizenId,
            UUID applicationId,UUID programId,String status,OffsetDateTime updatedAt){
        var data=new ConfirmedInboundEvents.M8SocialBenefitData(benefitId,citizenId,applicationId,programId,
            UUID.fromString("e6a13f58-2b9c-4d07-8e4f-7c5b1d9a0f26"),"Programa de prueba",
            UUID.fromString("a0f3c1d8-2e4b-4c97-8d16-5b7e9f2a3c80"),"TAX_EXEMPTION",
            "Exención municipal",null,status,"Prueba PostgreSQL",
            "APPROVED".equals(status)?LocalDate.of(2026,10,1):null,
            "FINALIZED".equals(status)?LocalDate.of(2026,10,31):null,updatedAt);
        return new ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent(UUID.randomUUID(),"socialBenefitUpdated",
            "1.0",updatedAt,"desarrollo-social",UUID.randomUUID(),null,data);
    }

    @Test void contextStartsAndFlywayAppliesEveryMigration(){
        assertThat(jdbc.queryForObject("select max(cast(version as integer)) from flyway_schema_history where success",Integer.class)).isEqualTo(31);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version is not null",Integer.class)).isEqualTo(30);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version='29'",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version='31'",Integer.class)).isOne();
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='tax_configuration'",String.class)).contains("submitted_by");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='liquidation_run'",String.class)).contains("submitted_by");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='social_benefit_rule'",String.class)).contains("submitted_by");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='ticket_case'",String.class))
            .contains("public_id","correlation_id","last_source_event_id","last_external_updated_at","request_type","subcategory");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='demo_user'",String.class))
            .contains("integration_actor_id");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='integration_event_log'",String.class))
            .contains("business_key");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='outbox_event'",String.class))
            .contains("idempotency_key");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where tablename='outbox_event'",String.class))
            .contains("uk_outbox_idempotency_key");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='payment_reversal_request'",String.class))
            .contains("resolution_reason");
        assertThat(jdbc.queryForList("select id from demo_bootstrap_lock",Integer.class)).containsExactly(1);
        assertThat(jdbc.queryForObject("select current_setting('server_version_num')::integer",Integer.class)).isBetween(170000,179999);
        assertThat(jdbc.queryForList("select code from tax_concept where code in ('TASA_SERVICIOS','ABL','PATENTE') order by code",String.class)).containsExactly("ABL","PATENTE","TASA_SERVICIOS");
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

    @Test void concurrentPlanConfigurationVersioningUsesUniqueSequentialVersions() throws Exception {
        authenticate();
        PaymentPlanConfiguration source=workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(2,6,BigDecimal.ZERO,new BigDecimal("5"),0,1,true,true,2,LocalDate.now().minusDays(1),null,true));
        var versions=new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var patchSeven=new ApiDtos.UpdatePaymentPlanConfigurationRequest(null,null,null,new BigDecimal("7"),null,null,null,null,null,null,null,null);
        var patchNine=new ApiDtos.UpdatePaymentPlanConfigurationRequest(null,null,null,new BigDecimal("9"),null,null,null,null,null,null,null,null);

        assertThat(runTogether(()->{versions.add(workflow.updateConfiguration(source.id,patchSeven).version);return null;},
            ()->{versions.add(workflow.updateConfiguration(source.id,patchNine).version);return null;})).containsOnlyNulls();

        assertThat(versions).hasSize(2).doesNotHaveDuplicates();
        assertThat(versions).containsExactlyInAnyOrder(source.version+1,source.version+2);
        assertThat(planConfigurations.findById(source.id).orElseThrow().interestRate).isEqualByComparingTo("5");
    }

    @Test void billPricingPreviewRunsReadOnlyOnPostgreSql() {
        authenticate();Debt debt=debt("BILL-PRICING",null);debt.dueDate=LocalDate.now().minusDays(2);debts.saveAndFlush(debt);Bill bill=billing.create(new ApiDtos.CreateBillRequest(debt.taxpayerId,List.of(debt.id),LocalDate.now().plusDays(10)));LateChargeRule rule=new LateChargeRule();rule.code="PG-BILL-"+UUID.randomUUID();rule.surchargeRate=new BigDecimal("10");rule.dailyInterestRate=new BigDecimal("1");rule.active=true;rule.validFrom=LocalDate.now();lateChargeRules.save(rule);long applicationsBefore=jdbc.queryForObject("select count(*) from late_charge_application",Long.class),adjustmentsBefore=jdbc.queryForObject("select count(*) from adjustment_request",Long.class);
        try {
            ApiDtos.BillDetailResponse detail=billing.pricingDetail(bill.id);
            assertThat(detail.updatedPayableAmount()).isEqualByComparingTo("112");
            assertThat(detail.pricingDetails()).singleElement().satisfies(price->{assertThat(price.pendingSurchargeAmount()).isEqualByComparingTo("10");assertThat(price.pendingInterestAmount()).isEqualByComparingTo("2");});
            assertThat(jdbc.queryForObject("select count(*) from late_charge_application",Long.class)).isEqualTo(applicationsBefore);
            assertThat(jdbc.queryForObject("select count(*) from adjustment_request",Long.class)).isEqualTo(adjustmentsBefore);
            assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100");
        } finally { rule.active=false;lateChargeRules.save(rule); }
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
            catalog.submit(taxConfiguration.id);TestActorSupport.asApprover(() -> catalog.approve(taxConfiguration.id));
            liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));
            Debt debt=debts.findByTaxpayerId(taxpayer.id).get(0);
            workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(1,1,BigDecimal.ZERO,new BigDecimal("25"),0,1,true,true,1,LocalDate.now().minusDays(1),null,true));
            PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(taxpayer.id,List.of(debt.id),1));
            PaymentPlanRequest granted=TestActorSupport.asApprover(() -> workflow.grant(request.id,null));
            Installment installment=installments.findByPaymentPlanIdOrderByNumber(granted.paymentPlanId).get(0);
            Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,null,PaymentMethod.CASH,new BigDecimal("100"),List.of(new ApiDtos.AllocationRequest(null,installment.id,new BigDecimal("100")))));
            PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
            assertThat(installment.principalAmount).isEqualByComparingTo("80.00");
            assertThat(installment.interestAmount).isEqualByComparingTo("20.00");
            assertThat(allocation.principalApplied).isEqualByComparingTo("80.00");
            assertThat(allocation.interestApplied).isEqualByComparingTo("20.00");
            assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isZero();
            PaymentReversalRequest reversal=reversals.request(payment.id,"Regresión PostgreSQL");TestActorSupport.asApprover(() -> reversals.approve(reversal.id));reversals.execute(reversal.id);
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
            var task=(java.util.concurrent.Callable<Integer>)()->{ready.countDown();start.await();return new TransactionTemplate(transactionManager).execute(status->{var claimed=outbox.findPublishable(PageRequest.of(0,50));if(!claimed.isEmpty()){try{Thread.sleep(150);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}claimed.forEach(x->x.status=OutboxStatus.PUBLISHED);}return (int)claimed.stream().filter(x->x.id.equals(event.id)).count();});};
            Future<Integer> first=pool.submit(task),second=pool.submit(task);assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)+second.get(10,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(outbox.findById(event.id).orElseThrow().status).isEqualTo(OutboxStatus.PUBLISHED);
        } finally { pool.shutdownNow(); }
    }

    @Test void failedOutboxEventsUseProgressiveBackoffWithoutDelayingPendingEvents() {
        OffsetDateTime now=OffsetDateTime.now();
        java.util.function.BiFunction<Integer,OffsetDateTime,OutboxEvent> failed=(attempts,lastAttempt)->{
            OutboxEvent event=new OutboxEvent();event.id=UUID.randomUUID();event.eventType="validation";event.targetModule="TEST";
            event.aggregateType="Validation";event.aggregateId=event.id.toString();event.payload="{}";event.status=OutboxStatus.FAILED;
            event.retryCount=attempts;event.lastAttemptAt=lastAttempt;event.createdAt=now;return event;
        };
        OutboxEvent pending=failed.apply(0,null);pending.status=OutboxStatus.PENDING;
        OutboxEvent legacyFailure=failed.apply(1,null);
        OutboxEvent retryOneDue=failed.apply(1,now.minusSeconds(15));
        OutboxEvent retryOneEarly=failed.apply(1,now.minusSeconds(1));
        OutboxEvent retryTwoDue=failed.apply(2,now.minusSeconds(25));
        OutboxEvent retryTwoEarly=failed.apply(2,now.minusSeconds(1));
        OutboxEvent retryThreeDue=failed.apply(3,now.minusSeconds(45));
        OutboxEvent retryThreeEarly=failed.apply(3,now.minusSeconds(1));
        OutboxEvent retryFourDue=failed.apply(4,now.minusSeconds(60));
        OutboxEvent retryFourEarly=failed.apply(4,now.minusSeconds(1));
        List<OutboxEvent> fixtures=List.of(pending,legacyFailure,retryOneDue,retryOneEarly,retryTwoDue,retryTwoEarly,
            retryThreeDue,retryThreeEarly,retryFourDue,retryFourEarly);
        outbox.saveAllAndFlush(fixtures);

        List<UUID> returned=outbox.findPublishable(PageRequest.of(0,200)).stream().map(event->event.id).toList();

        assertThat(returned).contains(pending.id,legacyFailure.id,retryOneDue.id,retryTwoDue.id,retryThreeDue.id,retryFourDue.id)
            .doesNotContain(retryOneEarly.id,retryTwoEarly.id,retryThreeEarly.id,retryFourEarly.id);
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

    @Test void coreM4EventsAreIdempotentlyDeferredAndReprocessableOnPostgreSql() {
        String cuit=uniqueDigits(11);
        concept("PERMIT_FEE",TaxConceptType.FEE,"M4");concept("COMMERCIAL_FINE",TaxConceptType.FINE,"M4");
        UUID feeId=UUID.randomUUID();
        var fee=new CoreM4Events.Envelope<>(feeId,"permitFeeGenerated","1.0",OffsetDateTime.now(),
            "habilitaciones",UUID.randomUUID(),null,
            new CoreM4Events.PermitFeeGeneratedData(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),cuit,new BigDecimal("50"),"ARS"));
        long logsBefore=integrationLogs.count(),obligationsBefore=externalObligations.count(),debtsBefore=debts.count();

        IntegrationEventLog pending=coreM4.consumePermitFee(fee);
        IntegrationEventLog duplicate=coreM4.consumePermitFee(fee);
        assertThat(duplicate.id).isEqualTo(pending.id);
        assertThat(pending.status).isEqualTo(IntegrationEventStatus.FAILED);
        assertThat(pending.errorMessage).isEqualTo(CoreM4Consumer.TAXPAYER_PENDING);
        assertThat(pending.errorMessage).doesNotContain(cuit);
        assertThat(pending.payload).contains(cuit,fee.data().id());
        assertThat(integrationLogs.count()).isEqualTo(logsBefore+1);

        taxpayer(TaxpayerType.ORGANIZATION,"M4-PG-"+feeId,null,cuit);
        IntegrationEventLog retried=integrationReprocess.reprocess(feeId,"Se proyectó la organización por CUIT");
        assertThat(retried.retryCount).isEqualTo(1);
        assertThat(retried.status).isEqualTo(IntegrationEventStatus.FAILED);
        assertThat(retried.errorMessage).startsWith(CoreM4Consumer.DUE_DATE_PENDING);
        assertThat(retried.errorMessage).doesNotContain(cuit);

        UUID fineId=UUID.randomUUID();
        var fine=new CoreM4Events.Envelope<>(fineId,"commercialFineGenerated","1.0",OffsetDateTime.now(),
            "habilitaciones",UUID.randomUUID(),null,
            new CoreM4Events.CommercialFineGeneratedData(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),cuit,
                new BigDecimal("75"),"ARS","Incumplimiento de prueba",OffsetDateTime.now(),"REF-PG-1",UUID.randomUUID().toString(),"M6"));
        IntegrationEventLog finePending=coreM4.consumeCommercialFine(fine);
        assertThat(finePending.errorMessage).startsWith(CoreM4Consumer.DUE_DATE_PENDING);
        assertThat(finePending.payload).contains(fine.data().id(),fine.data().actId(),"REF-PG-1",fine.data().sourceViolationId(),"M6");

        var repeatedOrigin=new CoreM4Events.Envelope<>(UUID.randomUUID(),fine.eventType(),fine.eventVersion(),
            fine.occurredAt(),fine.sourceModule(),fine.correlationId(),fine.causationId(),fine.data());
        assertThat(coreM4.consumeCommercialFine(repeatedOrigin).status).isEqualTo(IntegrationEventStatus.IGNORED);
        assertThatThrownBy(()->coreM4.consumeCommercialFine(new CoreM4Events.Envelope<>(UUID.randomUUID(),
            "commercialFineGenerated","1.0",OffsetDateTime.now(),"habilitaciones",null,null,
            new CoreM4Events.CommercialFineGeneratedData("FINE-BAD","EST-PG-1","ACT-PG-1",cuit,
                BigDecimal.TEN,"ARS","Prueba",OffsetDateTime.now(),"REF", "VIOLATION", "ambiente"))))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code).isEqualTo("INVALID_M4_VIOLATION_SOURCE"));
        assertThatThrownBy(()->coreM4.consumePermitFee(new CoreM4Events.Envelope<>(UUID.randomUUID(),
            "permitFeeGenerated","2.0",OffsetDateTime.now(),"habilitaciones",null,null,fee.data())))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code).isEqualTo("UNSUPPORTED_EVENT_VERSION"));
        assertThatThrownBy(()->coreM4.consumePermitFee(new CoreM4Events.Envelope<>(UUID.randomUUID(),
            "permitFeeGenerated","1.0",OffsetDateTime.now(),"habilitaciones",null,null,
            new CoreM4Events.PermitFeeGeneratedData("FEE-BAD","PA","EST","123",BigDecimal.TEN,"ARS"))))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code).isEqualTo("INVALID_EVENT"));
        var unsupportedCurrency=new CoreM4Events.Envelope<>(UUID.randomUUID(),"permitFeeGenerated","1.0",
            OffsetDateTime.now(),"habilitaciones",UUID.randomUUID(),null,
            new CoreM4Events.PermitFeeGeneratedData(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),cuit,BigDecimal.TEN,"EUR"));
        assertThat(coreM4.consumePermitFee(unsupportedCurrency).errorMessage).isEqualTo("M4_UNSUPPORTED_CURRENCY");
        var missingAmount=new CoreM4Events.Envelope<>(UUID.randomUUID(),"permitFeeGenerated","1.0",
            OffsetDateTime.now(),"habilitaciones",null,null,
            new CoreM4Events.PermitFeeGeneratedData("FEE-NO-AMOUNT","PA","EST",cuit,null,"ARS"));
        assertThatThrownBy(()->coreM4.consumePermitFee(missingAmount))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code).isEqualTo("INVALID_EVENT"));
        var sourceWithoutViolation=new CoreM4Events.Envelope<>(UUID.randomUUID(),"commercialFineGenerated","1.0",
            OffsetDateTime.now(),"habilitaciones",UUID.randomUUID(),null,
            new CoreM4Events.CommercialFineGeneratedData(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),cuit,BigDecimal.TEN,
                "ARS","Prueba",OffsetDateTime.now(),"REF",null,null));
        assertThat(coreM4.consumeCommercialFine(sourceWithoutViolation).errorMessage)
            .startsWith(CoreM4Consumer.DUE_DATE_PENDING);

        assertThat(externalObligations.count()).isEqualTo(obligationsBefore);
        assertThat(debts.count()).isEqualTo(debtsBefore);
    }

    @Test void coreM4FeeAndFineArrivalOrderDoesNotChangeTheirIndependentHandlingOnPostgreSql() {
        String cuit=uniqueDigits(11);taxpayer(TaxpayerType.ORGANIZATION,"M4-ORDER-"+UUID.randomUUID(),null,cuit);
        concept("PERMIT_FEE",TaxConceptType.FEE,"M4");concept("COMMERCIAL_FINE",TaxConceptType.FINE,"M4");
        UUID fineFirstId=UUID.randomUUID(),feeSecondId=UUID.randomUUID(),feeFirstId=UUID.randomUUID(),fineSecondId=UUID.randomUUID();
        var fineFirst=new CoreM4Events.Envelope<>(fineFirstId,"commercialFineGenerated","1.0",OffsetDateTime.now(),
            "habilitaciones",UUID.randomUUID(),null,new CoreM4Events.CommercialFineGeneratedData(UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),UUID.randomUUID().toString(),cuit,BigDecimal.TEN,"ARS","Prueba",OffsetDateTime.now(),"REF-A",null,null));
        var feeSecond=new CoreM4Events.Envelope<>(feeSecondId,"permitFeeGenerated","1.0",OffsetDateTime.now(),
            "habilitaciones",UUID.randomUUID(),null,new CoreM4Events.PermitFeeGeneratedData(UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),UUID.randomUUID().toString(),cuit,BigDecimal.TEN,"ARS"));
        var feeFirst=new CoreM4Events.Envelope<>(feeFirstId,"permitFeeGenerated","1.0",OffsetDateTime.now(),
            "habilitaciones",UUID.randomUUID(),null,new CoreM4Events.PermitFeeGeneratedData(UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),UUID.randomUUID().toString(),cuit,BigDecimal.TEN,"ARS"));
        var fineSecond=new CoreM4Events.Envelope<>(fineSecondId,"commercialFineGenerated","1.0",OffsetDateTime.now(),
            "habilitaciones",UUID.randomUUID(),null,new CoreM4Events.CommercialFineGeneratedData(UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),UUID.randomUUID().toString(),cuit,BigDecimal.TEN,"ARS","Prueba",OffsetDateTime.now(),"REF-B",null,null));
        long obligationsBefore=externalObligations.count(),debtsBefore=debts.count();

        assertThat(coreM4.consumeCommercialFine(fineFirst).errorMessage).startsWith(CoreM4Consumer.DUE_DATE_PENDING);
        assertThat(coreM4.consumePermitFee(feeSecond).errorMessage).startsWith(CoreM4Consumer.DUE_DATE_PENDING);
        assertThat(coreM4.consumePermitFee(feeFirst).errorMessage).startsWith(CoreM4Consumer.DUE_DATE_PENDING);
        assertThat(coreM4.consumeCommercialFine(fineSecond).errorMessage).startsWith(CoreM4Consumer.DUE_DATE_PENDING);
        assertThat(externalObligations.count()).isEqualTo(obligationsBefore);
        assertThat(debts.count()).isEqualTo(debtsBefore);
    }

    @Test void m4PlanInstallmentPublishesOnlyThePrincipalAppliedWithOriginalCorrelation() throws Exception {
        authenticate();
        String cuit=uniqueDigits(11),reference=UUID.randomUUID().toString();
        TaxpayerReference organization=taxpayer(TaxpayerType.ORGANIZATION,"M4-PLAN-"+UUID.randomUUID(),null,cuit);
        TaxConcept taxConcept=concept("M4_PLAN_"+UUID.randomUUID(),TaxConceptType.FEE,"M4");
        UUID sourceEventId=UUID.randomUUID(),sourceCorrelation=UUID.randomUUID();
        var sourceEvent=new CoreM4Events.Envelope<>(sourceEventId,"permitFeeGenerated","1.0",OffsetDateTime.now(),
            "habilitaciones",sourceCorrelation,null,
            new CoreM4Events.PermitFeeGeneratedData(reference,UUID.randomUUID().toString(),UUID.randomUUID().toString(),cuit,new BigDecimal("100"),"ARS"));
        IntegrationEventLog sourceLog=new IntegrationEventLog();sourceLog.eventId=sourceEventId;sourceLog.externalEventId=sourceEventId.toString();
        sourceLog.eventType=sourceEvent.eventType();sourceLog.sourceModule=sourceEvent.sourceModule();sourceLog.direction=EventDirection.INBOUND;
        sourceLog.status=IntegrationEventStatus.PROCESSED;sourceLog.payload=json.writeValueAsString(sourceEvent);sourceLog.retryCount=0;
        sourceLog.occurredAt=sourceEvent.occurredAt();sourceLog.receivedAt=sourceEvent.occurredAt();integrationLogs.save(sourceLog);
        ExternalObligation origin=new ExternalObligation();origin.sourceModule="habilitaciones";origin.externalType=ExternalObligationType.PERMIT_FEE;
        origin.externalReferenceId=reference;origin.sourceEventId=sourceEventId;origin.externalTaxpayerType=TaxpayerType.ORGANIZATION;
        origin.externalTaxpayerId=cuit;origin.taxpayerId=organization.id;origin.taxConceptId=taxConcept.id;origin.amount=new BigDecimal("100");
        origin.dueDate=LocalDate.now().plusDays(30);origin.status=ExternalObligationStatus.PROCESSED;origin.retryCount=0;
        origin.receivedAt=origin.processedAt=OffsetDateTime.now();externalObligations.save(origin);
        Debt debt=new Debt();debt.taxpayerId=organization.id;debt.taxConceptId=taxConcept.id;debt.originType=DebtOriginType.EXTERNAL_OBLIGATION;
        debt.externalObligationId=origin.id;debt.originalAmount=debt.currentAmount=debt.outstandingBalance=new BigDecimal("100.00");
        debt.dueDate=origin.dueDate;debt.status=DebtStatus.PENDING;debt.createdAt=debt.updatedAt=OffsetDateTime.now();debts.saveAndFlush(debt);

        workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(2,2,BigDecimal.ZERO,
            new BigDecimal("10"),0,1,true,true,1,LocalDate.now().minusDays(1),null,true));
        PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(organization.id,List.of(debt.id),2));
        PaymentPlan plan=planRepository.findById(TestActorSupport.asApprover(() -> workflow.grant(request.id,null)).paymentPlanId).orElseThrow();
        Installment installment=installments.findByPaymentPlanIdOrderByNumber(plan.id).get(0);
        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(organization.id,PaymentMethod.CASH,
            installment.totalAmount,List.of(new ApiDtos.AllocationRequest(null,installment.id,installment.totalAmount))));
        PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
        OutboxEvent event=outbox.findByIdempotencyKey("outbound:paymentRegistered:PaymentAllocation:"+allocation.id+":Debt:"+debt.id).orElseThrow();
        var data=json.readTree(event.payload).path("data");

        assertThat(data.path("originType").asText()).isEqualTo("PERMIT_FEE");
        assertThat(data.path("originId").asText()).isEqualTo(reference);
        assertThat(data.has("infractionId")).isFalse();
        assertThat(data.path("amountApplied").decimalValue()).isEqualByComparingTo(allocation.principalApplied);
        assertThat(data.path("amountApplied").decimalValue()).isLessThan(installment.totalAmount);
        assertThat(json.readTree(event.payload).path("correlationId").asText()).isEqualTo(sourceCorrelation.toString());
        assertThat(outbox.findByIdempotencyKey("outbound:debtSettled:Debt:"+debt.id)).isEmpty();
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

    @Test void fractionalInstallmentSimulationPersistencePaymentAndReversalConserveCentsOnPostgreSql() {
        authenticate();
        Debt debt=debt("PG-MONEY-ROUNDING",null);
        debt.originalAmount=debt.currentAmount=debt.outstandingBalance=new BigDecimal("100.01");
        debts.saveAndFlush(debt);
        workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(2,2,new BigDecimal("10"),
            new BigDecimal("12"),0,1,true,true,1,LocalDate.now().minusDays(1),null,true));

        var simulation=workflow.simulate(new ApiDtos.PaymentPlanSimulationRequest(debt.taxpayerId,List.of(debt.id),2));
        PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2));
        PaymentPlanRequest granted=TestActorSupport.asApprover(() -> workflow.grant(request.id,null));
        PaymentPlan plan=planRepository.findById(granted.paymentPlanId).orElseThrow();
        List<Installment> schedule=installments.findByPaymentPlanIdOrderByNumber(plan.id);

        assertThat(simulation.regularInstallmentAmount()).isEqualByComparingTo("50.40");
        assertThat(schedule.get(1).totalAmount).isEqualByComparingTo(simulation.regularInstallmentAmount());
        assertThat(schedule.stream().map(x->x.totalAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(plan.totalPlanAmount);
        assertThat(schedule.stream().map(x->x.principalAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(plan.originalPrincipalAmount);
        assertThat(schedule.stream().map(x->x.interestAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(plan.financingInterestAmount);

        Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,null,PaymentMethod.CASH,
            new BigDecimal("5.04"),List.of(new ApiDtos.AllocationRequest(null,schedule.get(1).id,new BigDecimal("5.04")))));
        PaymentAllocation allocation=allocations.findByPaymentId(payment.id).get(0);
        assertThat(allocation.principalApplied.add(allocation.interestApplied)).isEqualByComparingTo(allocation.amount);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("95.51");

        PaymentReversalRequest reversal=reversals.request(payment.id,"Prueba monetaria PostgreSQL");
        TestActorSupport.asApprover(() -> reversals.approve(reversal.id));reversals.execute(reversal.id);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.01");
        assertThat(installments.findById(schedule.get(1).id).orElseThrow().paidAmount).isEqualByComparingTo("0.00");
    }

    @Test void planReversalAndCreditSingleExecutionLocksRunOnPostgreSql() throws Exception {
        authenticate();Debt planDebt=debt("PG-PLAN",null);List<Throwable> planFailures=runTogether(()->plans.grant(new ApiDtos.GrantPlanRequest(planDebt.id,3,BigDecimal.ZERO)),()->plans.grant(new ApiDtos.GrantPlanRequest(planDebt.id,3,BigDecimal.ZERO)));
        assertThat(planRepository.findAll().stream().filter(x->planDebt.id.equals(x.debtId)&&x.status==PaymentPlanStatus.ACTIVE)).hasSize(1);assertThat(planFailures.stream().filter(Objects::nonNull)).hasSize(1);
        Debt reversalDebt=debt("PG-REV",null);Payment payment=payments.register(new ApiDtos.RegisterPaymentRequest(reversalDebt.taxpayerId,PaymentMethod.CASH,new BigDecimal("120"),List.of(new ApiDtos.AllocationRequest(reversalDebt.id,new BigDecimal("120")))));PaymentReversalRequest reversal=reversals.request(payment.id,"PostgreSQL concurrente");TestActorSupport.asApprover(() -> reversals.approve(reversal.id));
        List<Throwable> reversalFailures=runTogether(()->reversals.execute(reversal.id),()->reversals.execute(reversal.id));assertThat(reversalFailures.stream().filter(Objects::nonNull)).hasSize(1);assertThat(paymentRepository.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.REVERSED);
        Debt source=debt("PG-CREDIT-SOURCE",null);Payment overPayment=payments.register(new ApiDtos.RegisterPaymentRequest(source.taxpayerId,PaymentMethod.CASH,new BigDecimal("120"),List.of(new ApiDtos.AllocationRequest(source.id,new BigDecimal("120")))));CreditBalance credit=creditRepository.findBySourcePaymentId(overPayment.id).orElseThrow();Debt first=debt("PG-CREDIT-A",source.taxpayerId),second=debt("PG-CREDIT-B",source.taxpayerId);
        List<Throwable> creditFailures=runTogether(()->credits.apply(credit.id,new ApiDtos.ApplyCreditBalanceRequest(first.id,new BigDecimal("15"))),()->credits.apply(credit.id,new ApiDtos.ApplyCreditBalanceRequest(second.id,new BigDecimal("15"))));assertThat(creditFailures.stream().filter(Objects::nonNull)).hasSize(1);assertThat(creditRepository.findById(credit.id).orElseThrow().availableAmount).isEqualByComparingTo("5.00");assertThat(creditApplications.findAll().stream().filter(x->credit.id.equals(x.creditBalanceId))).hasSize(1);
    }

    @Test void distintasCuotasDelMismoPlanNoPierdenCapitalAlPagarYRevertirConcurrentemente() throws Exception {
        authenticate();
        Debt debt=debt("PG-PLAN-SHARED",null);
        workflow.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(2,2,BigDecimal.ZERO,
            new BigDecimal("20"),0,1,true,true,1,LocalDate.now().minusDays(1),null,true));
        PaymentPlanRequest request=workflow.request(new ApiDtos.CreatePaymentPlanRequest(debt.taxpayerId,List.of(debt.id),2));
        Long planId=TestActorSupport.asApprover(() -> workflow.grant(request.id,null)).paymentPlanId;
        List<Installment> cuotas=installments.findByPaymentPlanIdOrderByNumber(planId);
        assertThat(cuotas).hasSize(2);
        var pagos=new java.util.concurrent.CopyOnWriteArrayList<Payment>();
        assertThat(runWithDebtLocked(debt.id,
            ()->{pagos.add(payments.register(pagoCuota(debt.taxpayerId,cuotas.get(0))));return null;},
            ()->{pagos.add(payments.register(pagoCuota(debt.taxpayerId,cuotas.get(1))));return null;})).containsOnlyNulls();
        PaymentPlan paid=planRepository.findById(planId).orElseThrow();
        assertThat(paid.paidAmount).isEqualByComparingTo(paid.totalPlanAmount);
        assertThat(paid.outstandingPlanAmount).isZero();
        assertThat(paid.status).isEqualTo(PaymentPlanStatus.COMPLETED);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isZero();
        assertThat(jdbc.queryForObject("select principal_paid_amount from payment_plan_debt where payment_plan_id=?",BigDecimal.class,planId)).isEqualByComparingTo("100");
        assertThat(pagos).hasSize(2);
        List<Long> reversalsIds=pagos.stream().map(p->{
            PaymentReversalRequest reversal=reversals.request(p.id,"Cuotas distintas simultáneas");
            TestActorSupport.asApprover(() -> reversals.approve(reversal.id));return reversal.id;
        }).toList();
        long outboxBefore=outbox.count();
        assertThat(runWithDebtLocked(debt.id,()->reversals.execute(reversalsIds.get(0)),
            ()->reversals.execute(reversalsIds.get(1)))).containsOnlyNulls();
        PaymentPlan restored=planRepository.findById(planId).orElseThrow();
        assertThat(restored.paidAmount).isZero();
        assertThat(restored.outstandingPlanAmount).isEqualByComparingTo(restored.totalPlanAmount);
        assertThat(restored.status).isEqualTo(PaymentPlanStatus.ACTIVE);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select principal_paid_amount from payment_plan_debt where payment_plan_id=?",BigDecimal.class,planId)).isZero();
        assertThat(jdbc.queryForObject("select remaining_principal_amount from payment_plan_debt where payment_plan_id=?",BigDecimal.class,planId)).isEqualByComparingTo("100");
        assertThat(installments.findByPaymentPlanIdOrderByNumber(planId)).allSatisfy(i->{
            assertThat(i.paidAmount).isZero();assertThat(i.outstandingAmount).isEqualByComparingTo(i.totalAmount);
        });
        for(Payment payment:pagos){
            assertThat(paymentRepository.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.REVERSED);
            assertThat(allocations.findByPaymentId(payment.id)).singleElement().satisfies(a->assertThat(a.status).isEqualTo("REVERSED"));
            assertThat(jdbc.queryForObject("select count(*) from audit_entry where entity_type='Payment' and entity_id=? and action='PAYMENT_REVERSED'",Long.class,payment.id.toString())).isEqualTo(1);
        }
        // Reintentar una ejecución ya confirmada no restaura capital ni duplica efectos.
        assertThatThrownBy(()->reversals.execute(reversalsIds.get(0))).isInstanceOf(BusinessException.class);
        assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100");
        assertThat(outbox.count()).isEqualTo(outboxBefore);
    }

    private ApiDtos.RegisterPaymentRequest pagoCuota(Long taxpayerId,Installment cuota){
        return new ApiDtos.RegisterPaymentRequest(taxpayerId,null,PaymentMethod.CASH,cuota.totalAmount,
            List.of(new ApiDtos.AllocationRequest(null,cuota.id,cuota.totalAmount)));
    }

    /** Retiene la deuda hasta que ambas operaciones esperan un lock real de PostgreSQL. */
    private List<Throwable> runWithDebtLocked(Long debtId,Callable<?> first,Callable<?> second) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        String marker="plan-race-"+UUID.randomUUID();
        List<Future<Throwable>> futures=new ArrayList<>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("select id from debt where id=? for update",Long.class,debtId);
                for(Callable<?> action:List.of(first,second)) futures.add(pool.submit(()->{
                    authenticate();
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(worker->{
                            jdbc.queryForObject("select set_config('application_name', ?, true)",String.class,marker);
                            try {action.call();} catch(RuntimeException ex){throw ex;} catch(Exception ex){throw new IllegalStateException(ex);}
                        });
                        return null;
                    } catch(Throwable ex){return ex;} finally {SecurityContextHolder.clearContext();}
                }));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                int waiting=0;
                while(System.nanoTime()<deadline){
                    jdbc.execute("select pg_stat_clear_snapshot()");
                    waiting=jdbc.queryForObject("select count(*) from pg_stat_activity where application_name=? and wait_event_type='Lock'",Integer.class,marker);
                    if(waiting==2)break;
                    try {Thread.sleep(25);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
                }
                assertThat(waiting).as("Ambas operaciones deben solaparse antes de liberar la deuda").isEqualTo(2);
            });
            List<Throwable> failures=new ArrayList<>();
            for(Future<Throwable> future:futures)failures.add(future.get(15,TimeUnit.SECONDS));
            return failures;
        } finally {pool.shutdownNow();}
    }

    @Test void optimizedQueriesRemainBoundedOnPostgreSql(){
        authenticate();Debt first=debt("PG-PERF-A",null),second=debt("PG-PERF-B",first.taxpayerId);List<Liquidation> liquidationPage=new ArrayList<>();liquidationPage.add(liquidationFor(first));liquidationPage.add(liquidationFor(second));
        Statistics statistics=entityManagerFactory.unwrap(SessionFactory.class).getStatistics();statistics.clear();liquidations.responses(new PageImpl<>(liquidationPage));assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.clear();responses.debts(new PageImpl<>(List.of(first,second)));assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        Bill bill=billing.create(new ApiDtos.CreateBillRequest(first.taxpayerId,List.of(first.id,second.id),LocalDate.now().plusDays(10)));statistics.clear();responses.bills(new PageImpl<>(List.of(bill)));assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.clear();indicators.summary(null,null);assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        statistics.clear();workflow.defaulted(PageRequest.of(0,20));assertThat(statistics.getPrepareStatementCount()).isBetween(1L,2L);
    }

    @Test void demoAuthLateChargesDueDatesAndReconciliationRunOnPostgreSql(){
        authenticate();String suffix=UUID.randomUUID().toString();String dni=uniqueDigits(8);
        TaxpayerReference taxpayer=taxpayer(TaxpayerType.CITIZEN,"PG-FULL-"+suffix,dni,null);
        var created=demoAuth.create(new DemoAuthController.CreateUserRequest("pg."+suffix,"clave-segura","Usuario PostgreSQL",DemoRole.TAXPAYER,taxpayer.id));
        assertThat(demoUsers.findById(created.id()).orElseThrow().passwordHash).startsWith("$2");
        assertThat(demoAuth.login(new DemoAuthController.LoginRequest("pg."+suffix,"clave-segura")).user().taxpayerId()).isEqualTo(taxpayer.id);
        Debt debt=debt("PG-FISCAL",taxpayer.id);debt.dueDate=LocalDate.now().minusDays(10);debts.save(debt);
        var preview=lateCharges.preview(debt.id,LocalDate.now());assertThat(preview.daysOverdue()).isEqualTo(10);assertThat(preview.totalAdjustment()).isPositive();
        var applied=lateCharges.apply(debt.id,LocalDate.now());assertThat(applied.applied()).isTrue();assertThat(lateCharges.apply(debt.id,LocalDate.now()).applied()).isFalse();
        var processing=dueDates.process(LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires")));assertThat(processing.debtsOverdue()).isGreaterThanOrEqualTo(1);
        Payment payment=new Payment();payment.taxpayerId=taxpayer.id;payment.paymentMethod=PaymentMethod.TRANSFER;payment.amount=payment.unallocatedAmount=new BigDecimal("25.00");payment.allocatedAmount=BigDecimal.ZERO.setScale(2);payment.status=PaymentStatus.CONFIRMED;payment.allocationStatus=PaymentAllocationStatus.UNALLOCATED;payment.origin=PaymentOrigin.ELECTRONIC;payment.receiptNumber="PG-REC-"+suffix;payment.registeredBy="postgres-it";payment.paidAt=payment.createdAt=OffsetDateTime.of(LocalDate.now().minusDays(2),LocalTime.of(23,30),ZoneOffset.ofHours(-3));paymentRepository.save(payment);
        var batch=reconciliations.importBatch(new FiscalProcessingController.ImportReconciliationRequest("PG-BATCH-"+suffix,List.of(new FiscalProcessingController.ReconciliationItemRequest("PG-TX-"+suffix,dni,new BigDecimal("25"),payment.paidAt))));
        assertThat(batch.reconciledItems()).isEqualTo(1);assertThat(batch.items()).singleElement().satisfies(x->assertThat(x.matchedPaymentId()).isEqualTo(payment.id));
        assertThat(jdbc.queryForObject("select count(*) from late_charge_application where debt_id=?",Integer.class,debt.id)).isEqualTo(1);
    }

    @Test void overdueDebtIsPublishedOnceForEligibleCitizensEvenWithoutLateChargeRule() throws Exception {
        authenticate();
        TaxpayerReference citizen=taxpayer(TaxpayerType.CITIZEN,"PG-OVERDUE-"+UUID.randomUUID(),uniqueDigits(8),null);
        citizen.canonicalCitizenId=UUID.randomUUID();taxpayers.save(citizen);
        Debt eligible=debt("OVERDUE-"+UUID.randomUUID(),citizen.id);LocalDate processingDate=uniqueProcessingDate(eligible.id,0);eligible.dueDate=processingDate.minusDays(1);debts.save(eligible);
        Debt paid=debt("OVERDUE-PAID-"+UUID.randomUUID(),citizen.id);paid.dueDate=processingDate.minusDays(1);paid.status=DebtStatus.PAID;debts.save(paid);
        Debt cancelled=debt("OVERDUE-CANCELLED-"+UUID.randomUUID(),citizen.id);cancelled.dueDate=processingDate.minusDays(1);cancelled.status=DebtStatus.CANCELLED;debts.save(cancelled);
        Debt withoutBalance=debt("OVERDUE-ZERO-"+UUID.randomUUID(),citizen.id);withoutBalance.dueDate=processingDate.minusDays(1);withoutBalance.outstandingBalance=BigDecimal.ZERO;debts.save(withoutBalance);
        Debt notYetDue=debt("OVERDUE-FUTURE-"+UUID.randomUUID(),citizen.id);notYetDue.dueDate=processingDate.plusDays(1);debts.save(notYetDue);
        TaxpayerReference organization=taxpayer(TaxpayerType.ORGANIZATION,"PG-OVERDUE-ORG-"+UUID.randomUUID(),null,uniqueDigits(11));
        Debt organizationDebt=debt("OVERDUE-ORG-"+UUID.randomUUID(),organization.id);organizationDebt.dueDate=processingDate.minusDays(1);debts.save(organizationDebt);

        FiscalProcessingController.DueDateResponse run=dueDates.process(processingDate);
        assertThat(run.errors()).isGreaterThanOrEqualTo(1); // no applicable surcharge rule does not suppress debtOverdue
        List<OutboxEvent> events=overdueEvents(eligible);
        assertThat(events).hasSize(1);
        OutboxEvent event=events.get(0);assertThat(event.targetModule).isEqualTo("CORE");assertThat(event.status).isEqualTo(OutboxStatus.PENDING);assertThat(event.idempotencyKey).isEqualTo("debtOverdue:Debt:"+eligible.id);
        var envelope=json.readTree(event.payload);
        assertThat(envelope.fieldNames()).toIterable().containsExactlyInAnyOrder("eventId","eventType","eventVersion","occurredAt","sourceModule","correlationId","causationId","data");
        assertThat(envelope.path("eventId").asText()).isEqualTo(event.id.toString());assertThat(envelope.path("eventType").asText()).isEqualTo("debtOverdue");
        assertThat(envelope.path("eventVersion").asText()).isEqualTo("1.0");assertThat(envelope.path("occurredAt").asText()).isNotBlank();
        assertThat(envelope.path("sourceModule").asText()).isEqualTo("rentas");assertThat(envelope.path("correlationId").asText()).isNotBlank();
        assertThat(envelope.path("causationId").isNull()).isTrue();
        var data=envelope.path("data");assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("debtId","citizenId","conceptCode","outstandingAmount","dueDate");
        assertThat(data.path("debtId").asText()).isEqualTo(eligible.integrationId.toString());assertThat(data.path("citizenId").asText()).isEqualTo(citizen.canonicalCitizenId.toString());
        assertThat(data.path("conceptCode").asText()).isEqualTo(concepts.findById(eligible.taxConceptId).orElseThrow().code);
        assertThat(data.path("outstandingAmount").decimalValue()).isEqualByComparingTo(eligible.outstandingBalance);
        assertThat(data.path("dueDate").asText()).isEqualTo(eligible.dueDate.toString());
        assertThat(overdueEvents(paid)).isEmpty();assertThat(overdueEvents(cancelled)).isEmpty();assertThat(overdueEvents(withoutBalance)).isEmpty();
        assertThat(overdueEvents(notYetDue)).isEmpty();assertThat(overdueEvents(organizationDebt)).isEmpty();

        assertThat(dueDates.process(processingDate).id()).isEqualTo(run.id());
        for(OutboxStatus status:List.of(OutboxStatus.PUBLISHED,OutboxStatus.FAILED,OutboxStatus.DEAD_LETTER)){
            event.status=status;outbox.save(event);dueDates.process(processingDate.plusDays(status.ordinal()));
            assertThat(overdueEvents(eligible)).hasSize(1);
        }
    }

    @Test void dueDateProcessingHandlesMoreRowsThanOneBatch() {
        authenticate();
        TaxpayerReference taxpayer=taxpayer(TaxpayerType.CITIZEN,"PG-DUE-BATCH-"+UUID.randomUUID(),uniqueDigits(8),null);
        Debt seed=debt("DUE-BATCH-SEED-"+UUID.randomUUID(),taxpayer.id);
        List<Debt> fixture=new ArrayList<>();fixture.add(seed);
        for(int i=1;i<101;i++){
            Debt debt=new Debt();debt.taxpayerId=taxpayer.id;debt.taxConceptId=seed.taxConceptId;debt.originType=DebtOriginType.LIQUIDATION;
            debt.liquidationId=seed.liquidationId;debt.configurationId=seed.configurationId;
            debt.originalAmount=debt.currentAmount=debt.outstandingBalance=new BigDecimal("10.00");debt.status=DebtStatus.PENDING;
            debt.dueDate=LocalDate.of(1800,1,1);debt.createdAt=debt.updatedAt=OffsetDateTime.now();fixture.add(debt);
        }
        seed.dueDate=LocalDate.of(1800,1,1);debts.save(seed);
        debts.saveAll(fixture);debts.flush();
        int version=planConfigurations.findAll().stream().mapToInt(config->config.version).max().orElse(0)+1;
        PaymentPlanConfiguration config=new PaymentPlanConfiguration();config.version=version;config.minimumInstallments=1;config.maximumInstallments=120;
        config.minimumDownPaymentPercentage=BigDecimal.ZERO;config.interestRate=BigDecimal.ZERO;config.graceDays=0;config.maxOverdueInstallments=101;
        config.partialInstallmentPaymentAllowed=true;config.refinancingAllowed=true;config.maxRefinancingCount=1;config.validFrom=LocalDate.of(1800,1,1);
        config.active=true;config.createdBy="postgres-it";config.createdAt=OffsetDateTime.now();planConfigurations.save(config);planConfigurations.flush();
        PaymentPlan plan=new PaymentPlan();plan.taxpayerId=taxpayer.id;plan.debtId=seed.id;plan.configurationId=config.id;plan.configurationVersion=config.version;
        plan.originalPrincipalAmount=plan.financedPrincipalAmount=plan.totalPlanAmount=plan.outstandingPlanAmount=new BigDecimal("101.00");
        plan.downPaymentAmount=plan.financingInterestAmount=plan.paidAmount=BigDecimal.ZERO;plan.installmentCount=101;plan.refinancingCount=0;
        plan.status=PaymentPlanStatus.ACTIVE;plan.grantedBy="postgres-it";plan.grantedAt=OffsetDateTime.now();planRepository.save(plan);planRepository.flush();
        List<Installment> installmentFixture=new ArrayList<>();for(int i=1;i<=101;i++){Installment installment=new Installment();installment.paymentPlanId=plan.id;
            installment.number=i;installment.type=InstallmentType.REGULAR;installment.principalAmount=BigDecimal.ONE;installment.interestAmount=BigDecimal.ZERO;
            installment.totalAmount=installment.outstandingAmount=BigDecimal.ONE;installment.paidAmount=BigDecimal.ZERO;installment.dueDate=LocalDate.of(1800,1,1);
            installment.status=InstallmentStatus.PENDING;installmentFixture.add(installment);}
        installments.saveAll(installmentFixture);installments.flush();
        LocalDate processingDate=LocalDate.of(1800,1,2);
        long started=System.nanoTime();

        var run=dueDates.process(processingDate);

        long elapsedMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        assertThat(run.debtsScanned()).isGreaterThanOrEqualTo(101);
        assertThat(run.debtsOverdue()).isEqualTo(101);
        assertThat(run.installmentsOverdue()).isEqualTo(101);
        assertThat(installments.findByPaymentPlanIdOrderByNumber(plan.id)).allMatch(installment->installment.status==InstallmentStatus.OVERDUE);
        System.out.printf("due-date fixture rows=101 elapsedMs=%d%n",elapsedMillis);
    }

    @Test void concurrentDueDateProcessingForSameDateReturnsSingleIdempotentRun() throws Exception {
        LocalDate date=LocalDate.of(1801,1,1);while(dueDateRuns.findByProcessingDate(date).isPresent())date=date.plusDays(1);
        LocalDate processingDate=date;
        AtomicReference<FiscalProcessingController.DueDateResponse> first=new AtomicReference<>(),second=new AtomicReference<>();
        assertThat(runTogether(()->{first.set(dueDates.process(processingDate));return null;},()->{second.set(dueDates.process(processingDate));return null;})).containsOnlyNulls();
        assertThat(first.get()).isNotNull();assertThat(second.get()).isNotNull();
        assertThat(first.get().id()).isEqualTo(second.get().id());
        assertThat(dueDates.process(processingDate).id()).isEqualTo(first.get().id());
    }

    @Test void concurrentOverdueProcessingDoesNotCreateDuplicateOutboxEvents() throws Exception {
        authenticate();Debt eligible=debt("OVERDUE-CONCURRENT-"+UUID.randomUUID(),null);
        TaxpayerReference taxpayer=taxpayers.findById(eligible.taxpayerId).orElseThrow();taxpayer.canonicalCitizenId=UUID.randomUUID();taxpayers.save(taxpayer);
        LocalDate firstDate=uniqueProcessingDate(eligible.id,2),secondDate=firstDate.plusDays(1);eligible.dueDate=firstDate.minusDays(1);debts.save(eligible);
        assertThat(runTogether(()->dueDates.process(firstDate),()->dueDates.process(secondDate))).containsOnlyNulls();
        assertThat(overdueEvents(eligible)).hasSize(1);
    }

    @Test void overdueOutboxFailureRollsBackTheDueDateProcessingTransaction() {
        authenticate();Debt eligible=debt("OVERDUE-ROLLBACK-"+UUID.randomUUID(),null);LocalDate date=uniqueProcessingDate(eligible.id,4);eligible.dueDate=date.minusDays(1);debts.save(eligible);
        TaxpayerReference taxpayer=taxpayers.findById(eligible.taxpayerId).orElseThrow();taxpayer.canonicalCitizenId=UUID.randomUUID();taxpayers.save(taxpayer);
        String aggregateId=eligible.id.toString();
        jdbc.execute("CREATE OR REPLACE FUNCTION fail_test_overdue_outbox() RETURNS trigger AS $$ BEGIN IF NEW.event_type='debtOverdue' AND NEW.aggregate_id='"+aggregateId+"' THEN RAISE EXCEPTION 'test overdue outbox failure'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_test_overdue_outbox BEFORE INSERT ON outbox_event FOR EACH ROW EXECUTE FUNCTION fail_test_overdue_outbox()");
        try {
            assertThatThrownBy(()->dueDates.process(date)).hasMessageContaining("test overdue outbox failure");
            assertThat(jdbc.queryForObject("select count(*) from due_date_processing where processing_date=?",Long.class,date)).isZero();
            assertThat(overdueEvents(eligible)).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_test_overdue_outbox ON outbox_event");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_test_overdue_outbox()");
        }
    }

    @Test void overdueDebtWithoutM1IdentityIsDeferredWithoutAnInvalidOutboxPayload(){
        authenticate();Debt eligible=debt("OVERDUE-NO-M1-"+UUID.randomUUID(),null);
        LocalDate date=uniqueProcessingDate(eligible.id,6);eligible.dueDate=date.minusDays(1);debts.save(eligible);
        dueDates.process(date);
        assertThat(overdueEvents(eligible)).isEmpty();
        IntegrationEventLog deferred=integrationLogs.findFirstByBusinessKeyOrderByIdDesc("outbound-deferred:debtOverdue:Debt:"+eligible.id).orElseThrow();
        assertThat(deferred.status).isEqualTo(IntegrationEventStatus.PENDING);
        assertThat(deferred.errorMessage).isEqualTo("CANONICAL_CITIZEN_ID_PENDING");
        assertThat(debts.findById(eligible.id).orElseThrow().isCollectible()).isTrue();

        TaxpayerReference linked=taxpayers.findById(eligible.taxpayerId).orElseThrow();
        linked.canonicalCitizenId=UUID.fromString("b25d34e2-c891-4abd-a966-4907555b0139");
        taxpayers.save(linked);
        dueDates.process(date.plusDays(1));
        assertThat(overdueEvents(eligible)).hasSize(1);
        assertThat(integrationLogs.findById(deferred.id).orElseThrow().status).isEqualTo(IntegrationEventStatus.PROCESSED);
    }

    @Test void municipalSettlementWithVerifiedCitizenPreparesOneSharedFact(){
        authenticate();
        Debt created=debt("M8-SETTLED-CANDIDATE-"+UUID.randomUUID(),null);
        TaxpayerReference linked=taxpayers.findById(created.taxpayerId).orElseThrow();
        linked.canonicalCitizenId=UUID.fromString("1fd8a8c2-a224-493a-8cec-139b683ed882");
        taxpayers.save(linked);
        payments.register(new ApiDtos.RegisterPaymentRequest(created.taxpayerId,PaymentMethod.CASH,new BigDecimal("100"),
            List.of(new ApiDtos.AllocationRequest(created.id,new BigDecimal("100")))));

        Debt paid=debts.findById(created.id).orElseThrow();
        assertThat(paid.status).isEqualTo(DebtStatus.PAID);
        var prepared=outbox.findAll().stream().filter(event -> "debtSettled".equals(event.eventType)
            &&paid.id.toString().equals(event.aggregateId)).toList();
        assertThat(prepared).hasSize(1);
        assertThat(prepared.get(0).idempotencyKey).isEqualTo("outbound:debtSettled:Debt:"+paid.id);
        JsonNode envelope;
        try{envelope=json.readTree(prepared.get(0).payload);}catch(Exception ex){throw new AssertionError(ex);}
        assertThat(envelope.path("correlationId").isNull()).isTrue();
        assertThat(envelope.path("causationId").isNull()).isTrue();
        assertThat(envelope.path("data").path("originType").asText()).isEqualTo("MUNICIPAL_TAX");
        assertThat(envelope.path("data").has("infractionId")).isFalse();
        assertThat(envelope.path("data").path("citizenId").asText()).isEqualTo(linked.canonicalCitizenId.toString());
        confirmedOutbox.debtSettled(paid);
        assertThat(outbox.findAll().stream().filter(event -> "debtSettled".equals(event.eventType)
            &&paid.id.toString().equals(event.aggregateId))).hasSize(1);

        var candidate=confirmedOutbox.municipalDebtSettledCandidate(paid);
        assertThat(candidate.debtId()).isEqualTo(paid.integrationId);
        assertThat(candidate.citizenId()).isEqualTo(linked.canonicalCitizenId);
        assertThat(candidate.originId()).isEqualTo(liquidationRecords.findById(paid.liquidationId).orElseThrow().integrationId.toString());
        assertThat(candidate.remainingBalance()).isZero();
        assertThat(candidate.outstandingBalance()).isZero();
    }

    @Test void municipalSettlementWithoutCanonicalCitizenRemainsDeferred(){
        authenticate();Debt created=debt("M8-SETTLED-NO-M1-"+UUID.randomUUID(),null);
        payments.register(new ApiDtos.RegisterPaymentRequest(created.taxpayerId,PaymentMethod.CASH,new BigDecimal("100"),
            List.of(new ApiDtos.AllocationRequest(created.id,new BigDecimal("100")))));
        assertThat(outbox.findAll().stream().noneMatch(event -> "debtSettled".equals(event.eventType)
            &&created.id.toString().equals(event.aggregateId))).isTrue();
        assertThat(integrationLogs.findFirstByBusinessKeyOrderByIdDesc("outbound-deferred:debtSettled:Debt:"+created.id)
            .orElseThrow().errorMessage).isEqualTo("CANONICAL_CITIZEN_ID_PENDING");
    }

    @Test void bootstrapAndArbitraryUsersPersistBcryptOnPostgreSql(){
        authenticate();
        // Sólo datos de este contenedor de pruebas: nunca omitir el bootstrap por orden de tests.
        jdbc.update("delete from demo_auth_session");
        jdbc.update("delete from demo_user");
        demoAuthService.bootstrap("test-bootstrap-secret",new DemoAuthController.BootstrapRequest("qa.supervisor","clave-segura","QA Supervisor"));
        assertThat(jdbc.queryForObject("select password_hash from demo_user where username='qa.supervisor'",String.class)).startsWith("$2");
        TaxpayerReference taxpayer=taxpayer(TaxpayerType.CITIZEN,"QA-OWN-"+UUID.randomUUID(),uniqueDigits(8),null);
        String suffix=UUID.randomUUID().toString().substring(0,8);
        demoAuth.create(new DemoAuthController.CreateUserRequest("qa.rentas."+suffix,"clave-segura","QA Rentas",DemoRole.RENTAS,null));
        demoAuth.create(new DemoAuthController.CreateUserRequest("qa.caja."+suffix,"clave-segura","QA Caja",DemoRole.CASHIER,null));
        demoAuth.create(new DemoAuthController.CreateUserRequest("qa.auditor."+suffix,"clave-segura","QA Auditor",DemoRole.AUDITOR,null));
        var contributor=demoAuth.create(new DemoAuthController.CreateUserRequest("qa.contribuyente."+suffix,"clave-segura","QA Contribuyente",DemoRole.TAXPAYER,taxpayer.id));
        List<String> hashes=jdbc.queryForList("select password_hash from demo_user where username like ?",String.class,"qa.%."+suffix);
        assertThat(hashes).hasSize(4).allMatch(hash->hash.startsWith("$2")&&!hash.contains("clave-segura"));
        var login=demoAuth.login(new DemoAuthController.LoginRequest("qa.contribuyente."+suffix,"clave-segura"));
        assertThat(login.token()).isNotEqualTo("dev-session");
        assertThat(login.user().taxpayerId()).isEqualTo(taxpayer.id);
        assertThat(contributor.role()).isEqualTo(DemoRole.TAXPAYER);
    }

    @Test void bootstrapConcurrenteSoloCreaUnSupervisorEnPostgreSql() throws Exception {
        jdbc.update("delete from demo_auth_session");
        jdbc.update("delete from demo_user");
        long auditsBefore=jdbc.queryForObject("select count(*) from audit_entry where action='DEMO_BOOTSTRAP_COMPLETED'",Long.class);
        List<Throwable> failures=runTogether(
            ()->demoAuthService.bootstrap("test-bootstrap-secret",new DemoAuthController.BootstrapRequest("bootstrap.uno","clave-segura","Uno")),
            ()->demoAuthService.bootstrap("test-bootstrap-secret",new DemoAuthController.BootstrapRequest("bootstrap.dos","clave-segura","Dos")));
        assertThat(failures.stream().filter(Objects::isNull)).hasSize(1);
        assertThat(failures.stream().filter(Objects::nonNull)).singleElement().satisfies(error->
            assertThat(error).isInstanceOfSatisfying(BusinessException.class,ex->{
                assertThat(ex.status).isEqualTo(409);assertThat(ex.code).isEqualTo("DEMO_BOOTSTRAP_ALREADY_COMPLETED");
            }));
        assertThat(demoUsers.findAll()).singleElement().satisfies(user->{
            assertThat(user.role).isEqualTo(DemoRole.SUPERVISOR);
            var login=demoAuthService.login(new DemoAuthController.LoginRequest(user.username,"clave-segura"));
            assertThat(demoAuthService.me(login.token()).id()).isEqualTo(user.id);
            assertThat(jdbc.queryForObject("select token_hash from demo_auth_session where demo_user_id=?",String.class,user.id))
                .isEqualTo(DemoAuthService.hashToken(login.token())).isNotEqualTo(login.token());
        });
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where action='DEMO_BOOTSTRAP_COMPLETED'",Long.class)).isEqualTo(auditsBefore+1);
    }

    @Test void concurrentBulkExecutionCommitsOnceOnPostgreSql() throws Exception {
        authenticate();
        TaxConcept concept = concept("PG_BULK_" + UUID.randomUUID(), TaxConceptType.FEE, "M5");
        TaxConfiguration config = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            concept.id, CalculationType.FIXED, null, new BigDecimal("100.25"), null, null,
            true, true, LocalDate.now().minusDays(1), null));
        catalog.submit(config.id); TestActorSupport.asApprover(() -> catalog.approve(config.id));
        List<ApiDtos.LiquidationRunItemRequest> population = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            TaxpayerReference owner = taxpayer(TaxpayerType.CITIZEN, "PG-BULK-" + UUID.randomUUID(), uniqueDigits(8), null);
            population.add(new ApiDtos.LiquidationRunItemRequest(owner.id, BigDecimal.ZERO));
        }
        LiquidationRun run = bulkRuns.create(new ApiDtos.CreateLiquidationRunRequest(concept.id,
            YearMonth.now().toString(), LocalDate.now().plusDays(30), population));
        bulkRuns.preview(run.id); bulkRuns.submit(run.id); TestActorSupport.asApprover(() -> bulkRuns.approve(run.id, "QA"));
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

    @Test void percentageStrategyPreviewAndPersistedLiquidationMatchOnPostgreSql() {
        authenticate();
        TaxpayerReference owner = taxpayer(TaxpayerType.CITIZEN, "PG-STRATEGY-" + UUID.randomUUID(), uniqueDigits(8), null);
        TaxConcept concept = concept("PG_STRATEGY_" + UUID.randomUUID(), TaxConceptType.FEE, "M5");
        TaxConfiguration config = catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(
            concept.id, CalculationType.PERCENTAGE, new BigDecimal("2.0000"), null, null, null,
            true, true, LocalDate.now().minusDays(1), null));
        catalog.submit(config.id);
        TestActorSupport.asApprover(() -> catalog.approve(config.id));
        var request = new ApiDtos.LiquidationRequest(owner.id, concept.id, YearMonth.now().toString(),
            new BigDecimal("100000.25"), LocalDate.now().plusDays(30));

        var preview = liquidations.preview(request);
        Liquidation created = liquidations.create(request);

        assertThat(preview.baseAmount()).isEqualByComparingTo("2000.01");
        assertThat(preview.finalAmount()).isEqualByComparingTo("2000.01");
        assertThat(created.baseAmount).isEqualByComparingTo(preview.baseAmount());
        assertThat(created.finalAmount).isEqualByComparingTo(preview.finalAmount());
        assertThat(debts.findByLiquidationId(created.id).orElseThrow().outstandingBalance)
            .isEqualByComparingTo(preview.finalAmount());
    }

    private void assertEquation(Payment payment){Payment stored=paymentRepository.findById(payment.id).orElseThrow();assertThat(stored.allocatedAmount.add(stored.unallocatedAmount)).isEqualByComparingTo(stored.amount);}
    private LocalDate uniqueProcessingDate(Long debtId,int offset){return LocalDate.of(1900,1,1).plusDays(debtId*10+offset);}
    private List<OutboxEvent> overdueEvents(Debt debt){return outbox.findAll().stream().filter(x->x.eventType.equals("debtOverdue")&&x.targetModule.equals("CORE")&&x.aggregateType.equals("Debt")&&x.aggregateId.equals(debt.id.toString())).toList();}
    private void authenticate(){var authorities=List.of("RENTAS","SUPERVISOR","CASHIER").stream().map(x->new SimpleGrantedAuthority("ROLE_"+x)).toList();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("postgres-it",null),null,authorities));}
    private void authenticateTaxpayer(Long taxpayerId){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("postgres-it-taxpayer-"+UUID.randomUUID(),taxpayerId),null,List.of(new SimpleGrantedAuthority("ROLE_TAXPAYER"))));}
    private MockHttpServletResponse postElectronic(String session,String key,ApiDtos.ElectronicPaymentRequest request)throws Exception{SecurityContextHolder.clearContext();return mvc.perform(post("/api/v1/electronic-payments").header("X-Demo-Session",session).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request))).andReturn().getResponse();}
    private Debt debt(String suffix,Long taxpayerId){if(taxpayerId==null)taxpayerId=taxpayer(TaxpayerType.CITIZEN,"PG-"+suffix+"-"+UUID.randomUUID(),uniqueDigits(8),null).id;TaxConcept concept=concept("PG_"+suffix+"_"+UUID.randomUUID(),TaxConceptType.FEE,"M5");TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(configuration.id);TestActorSupport.asApprover(() -> catalog.approve(configuration.id));Liquidation liquidation=liquidations.create(new ApiDtos.LiquidationRequest(taxpayerId,concept.id,YearMonth.now().plusMonths(Math.abs(suffix.hashCode()%12)).toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));return debts.findByTaxpayerId(taxpayerId).stream().filter(x->x.liquidationId.equals(liquidation.id)).findFirst().orElseThrow();}
    private Liquidation liquidationFor(Debt debt){return jdbc.queryForObject("select id from liquidation where id=?",(rs,n)->{Liquidation l=new Liquidation();l.id=rs.getLong(1);return l;},debt.liquidationId);}
    private TaxConcept concept(String code,TaxConceptType type,String module){return concepts.findByCode(code).orElseGet(()->catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(code,code,null,type,module)));}
    private TaxpayerReference taxpayer(TaxpayerType type,String external,String dni,String cuit){TaxpayerReference t=new TaxpayerReference();t.taxpayerType=type;t.externalId=external;t.dni=dni;t.cuit=cuit;t.displayName="PostgreSQL contract";t.externalStatus=TaxpayerStatus.ACTIVE;t.createdAt=t.updatedAt=java.time.OffsetDateTime.now();return taxpayers.save(t);}
    private String uniqueDigits(int length){String digits=Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());return (digits+"00000000000000000000").substring(0,length);}
    private ConfirmedInboundEvents.M7InfractionData m7Data(UUID id,String debtor,ConfirmedInboundEvents.M7DebtorIdType type,BigDecimal finalAmount){return new ConfirmedInboundEvents.M7InfractionData(id,debtor,type,"AA111AA","TEST",java.time.OffsetDateTime.now(),new BigDecimal("50"),List.of(),finalAmount,"INS-1",new ConfirmedInboundEvents.M7Location("Calle","Esquina",new BigDecimal("-34.60"),new BigDecimal("-58.40")));}
    private ConfirmedInboundEvents.M7InfractionConfirmedEvent m7Event(UUID eventId,String source,ConfirmedInboundEvents.M7InfractionData data){return new ConfirmedInboundEvents.M7InfractionConfirmedEvent(eventId,"infractionConfirmed","1.0",java.time.OffsetDateTime.now(),source,UUID.randomUUID(),null,data);}
    private List<Throwable> runTogether(Callable<?> first,Callable<?> second)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);try{List<Future<Throwable>> futures=List.of(pool.submit(task(first,ready,start)),pool.submit(task(second,ready,start)));assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();List<Throwable> result=new ArrayList<>();for(Future<Throwable> future:futures)result.add(future.get(15,TimeUnit.SECONDS));return result;}finally{pool.shutdownNow();}}
    private Callable<Throwable> task(Callable<?> action,CountDownLatch ready,CountDownLatch start){return ()->{authenticate();ready.countDown();start.await();try{action.call();return null;}catch(Throwable ex){return ex;}finally{SecurityContextHolder.clearContext();}};}
}
