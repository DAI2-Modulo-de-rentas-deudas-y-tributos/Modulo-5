package ar.gob.municipalidad.rentas;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BenefitScopeAndAllocationAccessTests {
    @Autowired MockMvc mvc;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;
    @Autowired SocialBenefitRepository benefits;
    @Autowired SocialBenefitTaxConceptRepository benefitConcepts;
    @Autowired PaymentRepository payments;
    @Autowired PaymentAllocationRepository allocations;
    @Autowired DemoUserRepository demoUsers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;
    private DemoAuthSessions demo;

    @BeforeEach void setUp() {
        demo=new DemoAuthSessions(demoUsers,taxpayers,encoder,demoAuth);
    }

    @Test void benefitScopeIsExposedByListDetailAndTaxpayerEndpoints() throws Exception {
        TaxpayerReference taxpayer=taxpayer("SCOPE");
        SocialBenefitReference benefit=benefit(taxpayer,"BEN-SCOPE");
        link(benefit,concept("TASA_SERVICIOS"));
        link(benefit,concept("ABL"));

        mvc.perform(get("/api/v1/social-benefits").header("X-Demo-Session",demo.token(DemoRole.RENTAS)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].externalBenefitId").value("BEN-SCOPE"))
            .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.content[0].taxConceptCodes[0]").value("ABL"))
            .andExpect(jsonPath("$.content[0].taxConceptCodes[1]").value("TASA_SERVICIOS"));

        mvc.perform(get("/api/v1/social-benefits/{id}",benefit.id).header("X-Demo-Session",demo.token(DemoRole.RENTAS)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(benefit.id))
            .andExpect(jsonPath("$.discountPercentage").value(50.0))
            .andExpect(jsonPath("$.taxConceptCodes.length()").value(2));

        mvc.perform(get("/api/v1/taxpayers/{id}/benefits",taxpayer.id).header("X-Demo-Session",demo.token(DemoRole.TAXPAYER,taxpayer.id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].externalBenefitId").value("BEN-SCOPE"))
            .andExpect(jsonPath("$[0].taxConceptCodes.length()").value(2));
    }

    @Test void benefitWithoutConceptsExposesEmptyScope() throws Exception {
        SocialBenefitReference benefit=benefit(taxpayer("EMPTY"),"BEN-EMPTY");

        mvc.perform(get("/api/v1/social-benefits/{id}",benefit.id).header("X-Demo-Session",demo.token(DemoRole.AUDITOR)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.externalBenefitId").value("BEN-EMPTY"))
            .andExpect(jsonPath("$.taxConceptCodes").isArray())
            .andExpect(jsonPath("$.taxConceptCodes").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value=DemoRole.class,names={"RENTAS","AUDITOR","SUPERVISOR"})
    void authorizedStaffCanReadPaymentAllocations(DemoRole role) throws Exception {
        Payment payment=payment(taxpayer("STAFF-"+role));
        allocation(payment);

        mvc.perform(get("/api/v1/payments/{id}/allocations",payment.id).header("X-Demo-Session",demo.token(role)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].paymentId").value(payment.id))
            .andExpect(jsonPath("$[0].amount").value(25.0));
    }

    @Test void taxpayerCanReadOwnPaymentAllocations() throws Exception {
        TaxpayerReference owner=taxpayer("OWNER");Payment payment=payment(owner);allocation(payment);

        mvc.perform(get("/api/v1/payments/{id}/allocations",payment.id).header("X-Demo-Session",demo.token(DemoRole.TAXPAYER,owner.id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].paymentId").value(payment.id));
    }

    @Test void taxpayerCannotReadAnotherTaxpayersAllocations() throws Exception {
        Payment payment=payment(taxpayer("OWNER-FOREIGN"));allocation(payment);
        TaxpayerReference stranger=taxpayer("STRANGER");

        mvc.perform(get("/api/v1/payments/{id}/allocations",payment.id).header("X-Demo-Session",demo.token(DemoRole.TAXPAYER,stranger.id)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
    }

    @Test void missingPaymentReturnsNotFound() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}/allocations",999999L).header("X-Demo-Session",demo.token(DemoRole.RENTAS)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private TaxpayerReference taxpayer(String suffix) {
        TaxpayerReference taxpayer=new TaxpayerReference();taxpayer.taxpayerType=TaxpayerType.CITIZEN;taxpayer.externalId="ACCESS-"+suffix+"-"+UUID.randomUUID();taxpayer.dni=String.valueOf(Math.abs(UUID.randomUUID().hashCode()));taxpayer.displayName="Contribuyente "+suffix;taxpayer.externalStatus=TaxpayerStatus.ACTIVE;taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();return taxpayers.save(taxpayer);
    }

    private TaxConcept concept(String code) {
        TaxConcept concept=new TaxConcept();concept.code=code;concept.name=code;concept.type=TaxConceptType.FEE;concept.originModule="M5";concept.active=true;concept.createdAt=concept.updatedAt=OffsetDateTime.now();return concepts.save(concept);
    }

    private SocialBenefitReference benefit(TaxpayerReference taxpayer,String externalId) {
        SocialBenefitReference benefit=new SocialBenefitReference();benefit.externalBenefitId=externalId;benefit.taxpayerId=taxpayer.id;benefit.externalCitizenId=taxpayer.externalId;benefit.benefitType="TAX_DISCOUNT";benefit.externalStatus="ACTIVE";benefit.calculatedStatus=SocialBenefitStatus.ACTIVE;benefit.benefitsPayload="[]";benefit.discountPercentage=new BigDecimal("50.00");benefit.validFrom=LocalDate.now();benefit.validUntil=LocalDate.now().plusYears(1);benefit.sourceEventId=UUID.randomUUID();benefit.externalSourceEventId=benefit.sourceEventId.toString();benefit.updatedAt=OffsetDateTime.now();return benefits.save(benefit);
    }

    private void link(SocialBenefitReference benefit,TaxConcept concept) {
        SocialBenefitTaxConcept link=new SocialBenefitTaxConcept();link.socialBenefitId=benefit.id;link.taxConceptId=concept.id;benefitConcepts.save(link);
    }

    private Payment payment(TaxpayerReference taxpayer) {
        Payment payment=new Payment();payment.taxpayerId=taxpayer.id;payment.paymentMethod=PaymentMethod.CASH;payment.amount=new BigDecimal("25.00");payment.allocatedAmount=new BigDecimal("25.00");payment.unallocatedAmount=BigDecimal.ZERO.setScale(2);payment.status=PaymentStatus.CONFIRMED;payment.allocationStatus=PaymentAllocationStatus.FULLY_ALLOCATED;payment.origin=PaymentOrigin.CASHIER;payment.receiptNumber="REC-"+UUID.randomUUID();payment.registeredBy="test";payment.paidAt=payment.createdAt=OffsetDateTime.now();return payments.save(payment);
    }

    private void allocation(Payment payment) {
        PaymentAllocation allocation=new PaymentAllocation();allocation.paymentId=payment.id;allocation.targetType=AllocationTargetType.DEBT;allocation.debtId=999999L;allocation.amount=new BigDecimal("25.00");allocation.principalApplied=new BigDecimal("25.00");allocation.interestApplied=BigDecimal.ZERO.setScale(2);allocation.status="ACTIVE";allocation.allocatedBy="test";allocation.allocatedAt=OffsetDateTime.now();allocations.save(allocation);
    }
}
