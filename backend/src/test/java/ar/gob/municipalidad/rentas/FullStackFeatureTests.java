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
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class FullStackFeatureTests {
    @Autowired DemoAuthController auth;@Autowired DemoUserRepository users;@Autowired CatalogService catalog;
    @Autowired DebtRepository debts;@Autowired LateChargeRuleRepository rules;@Autowired LateChargeService lateCharges;
    @Autowired DueDateService dueDates;@Autowired AdjustmentRepository adjustments;@Autowired PaymentRepository payments;
    @Autowired ReconciliationService reconciliations;@Autowired ElectronicReconciliationItemRepository reconciliationItems;

    @BeforeEach void authenticate(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("supervisor.test",null),null,List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void demoUserPersistsWithBcryptAndValidatesCredentials(){
        var created=auth.create(new DemoAuthController.CreateUserRequest(" Integration.User ","clave-segura","Integración",DemoRole.RENTAS,null));
        DemoUser stored=users.findById(created.id()).orElseThrow();assertThat(stored.username).isEqualTo("integration.user");assertThat(stored.passwordHash).startsWith("$2").doesNotContain("clave-segura");
        assertThat(auth.login(new DemoAuthController.LoginRequest("integration.user","clave-segura")).user().role()).isEqualTo(DemoRole.RENTAS);
        assertThatThrownBy(()->auth.login(new DemoAuthController.LoginRequest("integration.user","incorrecta"))).isInstanceOf(BusinessException.class).hasMessageContaining("incorrectos");
        assertThatThrownBy(()->auth.create(new DemoAuthController.CreateUserRequest("integration.user","otra-clave","Duplicado",DemoRole.RENTAS,null))).isInstanceOf(BusinessException.class);
    }

    @Test void taxpayerDemoUserRequiresExistingTaxpayer(){
        assertThatThrownBy(()->auth.create(new DemoAuthController.CreateUserRequest("contribuyente","clave-segura","Contribuyente",DemoRole.TAXPAYER,999999L))).isInstanceOf(BusinessException.class);
        TaxpayerReference taxpayer=taxpayer("AUTH-1","30111222");var created=auth.create(new DemoAuthController.CreateUserRequest("contribuyente","clave-segura","Contribuyente",DemoRole.TAXPAYER,taxpayer.id));assertThat(created.taxpayerId()).isEqualTo(taxpayer.id);
    }

    @Test void lateChargePreviewConfirmationAndDailyProcessingAreIdempotent(){
        Debt debt=debt(taxpayer("LATE-1","30111223"),new BigDecimal("100.00"),LocalDate.now().minusDays(10));rule();
        var preview=lateCharges.preview(debt.id,LocalDate.now());assertThat(preview.surchargeAmount()).isEqualByComparingTo("2.00");assertThat(preview.interestAmount()).isEqualByComparingTo("1.00");assertThat(preview.applied()).isFalse();
        var applied=lateCharges.apply(debt.id,LocalDate.now());var duplicate=lateCharges.apply(debt.id,LocalDate.now());assertThat(applied.applied()).isTrue();assertThat(duplicate.applied()).isFalse();assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("103.00");assertThat(adjustments.count()).isEqualTo(2);
        var first=dueDates.process(LocalDate.now());var again=dueDates.process(LocalDate.now());assertThat(again.id()).isEqualTo(first.id());assertThat(again.skippedAlreadyProcessed()).isGreaterThanOrEqualTo(1);
    }

    @Test void reconciliationPersistsStatusesAndManualResolution(){
        TaxpayerReference taxpayer=taxpayer("REC-1","30111224");Payment payment=payment(taxpayer,new BigDecimal("250.00"),OffsetDateTime.now());
        var batch=reconciliations.importBatch(new FiscalProcessingController.ImportReconciliationRequest("LOTE-1",List.of(
            new FiscalProcessingController.ReconciliationItemRequest("TX-OK","30111224",new BigDecimal("250"),payment.paidAt),
            new FiscalProcessingController.ReconciliationItemRequest("TX-OBS","30111224",new BigDecimal("251"),payment.paidAt),
            new FiscalProcessingController.ReconciliationItemRequest("TX-NO","00000000",new BigDecimal("10"),payment.paidAt))));
        assertThat(batch.reconciledItems()).isEqualTo(1);assertThat(batch.observedItems()).isEqualTo(1);assertThat(batch.notFoundItems()).isEqualTo(1);
        var observed=batch.items().stream().filter(x->x.status()==ReconciliationStatus.OBSERVED).findFirst().orElseThrow();var resolved=reconciliations.resolve(observed.id(),new FiscalProcessingController.ResolveReconciliationRequest(payment.id,"Validación manual"));assertThat(resolved.status()).isEqualTo(ReconciliationStatus.CONCILIATED);
        assertThat(reconciliations.importBatch(new FiscalProcessingController.ImportReconciliationRequest("LOTE-1",List.of(new FiscalProcessingController.ReconciliationItemRequest("IGNORED","x",BigDecimal.ONE,OffsetDateTime.now())))).id()).isEqualTo(batch.id());
    }

    private TaxpayerReference taxpayer(String external,String dni){return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,external,dni,null,"Persona "+external));}
    private Debt debt(TaxpayerReference taxpayer,BigDecimal amount,LocalDate due){TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("C-"+taxpayer.externalId,"Concepto",null,TaxConceptType.FEE,"M5"));Debt d=new Debt();d.taxpayerId=taxpayer.id;d.taxConceptId=concept.id;d.originType=DebtOriginType.LIQUIDATION;d.liquidationId=100000L+taxpayer.id;d.originalAmount=d.currentAmount=d.outstandingBalance=amount;d.dueDate=due;d.status=DebtStatus.PENDING;d.createdAt=d.updatedAt=OffsetDateTime.now();return debts.save(d);}
    private LateChargeRule rule(){LateChargeRule r=new LateChargeRule();r.code="TEST";r.surchargeRate=new BigDecimal("2");r.dailyInterestRate=new BigDecimal("0.1");r.active=true;r.validFrom=LocalDate.now().minusYears(1);return rules.save(r);}
    private Payment payment(TaxpayerReference taxpayer,BigDecimal amount,OffsetDateTime paidAt){Payment p=new Payment();p.taxpayerId=taxpayer.id;p.paymentMethod=PaymentMethod.TRANSFER;p.amount=amount;p.allocatedAmount=BigDecimal.ZERO.setScale(2);p.unallocatedAmount=amount;p.status=PaymentStatus.CONFIRMED;p.allocationStatus=PaymentAllocationStatus.UNALLOCATED;p.origin=PaymentOrigin.ELECTRONIC;p.receiptNumber="REC-"+System.nanoTime();p.registeredBy="test";p.paidAt=paidAt;p.createdAt=paidAt;return payments.save(p);}
}
