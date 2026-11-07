package ar.gob.municipalidad.rentas;

import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.jdbc.Sql(statements="INSERT INTO demo_bootstrap_lock(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM demo_bootstrap_lock WHERE id=1)")
class BeanValidationContractTests {
    @Autowired Validator validator;
    @Autowired MockMvc mvc;
    @Autowired PaymentRepository payments;
    @Autowired LiquidationRepository liquidations;
    @Autowired LiquidationRunRepository liquidationRuns;
    @Autowired AdjustmentRepository adjustments;
    @Autowired PaymentPlanConfigurationRepository planConfigurations;
    @Autowired DemoUserRepository users;
    @Autowired DemoAuthSessionRepository sessions;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService auth;

    private DemoAuthSessions demo;

    @BeforeEach void setUp() {
        demo=new DemoAuthSessions(users,taxpayers,encoder,auth);
    }

    @Test void validatesNestedAllocationsAndRejectsNullElements() {
        var request=new ApiDtos.RegisterPaymentRequest(1L,null,PaymentMethod.CASH,BigDecimal.TEN,
            Arrays.asList(new ApiDtos.AllocationRequest(null,null,BigDecimal.ZERO),null));

        Set<String> paths=validator.validate(request).stream()
            .map(error->error.getPropertyPath().toString()).collect(Collectors.toSet());

        assertThat(paths).contains("allocations[0].amount","allocations[1].<list element>");
        assertThat(validator.validate(new ApiDtos.RegisterPaymentRequest(1L,null,PaymentMethod.CASH,
            BigDecimal.TEN,null))).isEmpty();
    }

    @Test void validatesIdentifiersAndNestedBulkItems() {
        var bill=new ApiDtos.CreateBillRequest(1L,Arrays.asList(2L,null),LocalDate.now().plusDays(1));
        var run=new ApiDtos.CreateLiquidationRunRequest(1L,"2026-10",LocalDate.now().plusDays(1),
            Arrays.asList(new ApiDtos.LiquidationRunItemRequest(2L,new BigDecimal("-1")),null));

        Set<String> billPaths=validator.validate(bill).stream()
            .map(error->error.getPropertyPath().toString()).collect(Collectors.toSet());
        Set<String> runPaths=validator.validate(run).stream()
            .map(error->error.getPropertyPath().toString()).collect(Collectors.toSet());

        assertThat(billPaths).contains("debtIds[1].<list element>");
        assertThat(runPaths).contains("items[0].taxableBase","items[1].<list element>");
    }

    @Test void checksRequiredTextNumericBoundsAndKeepsConditionalAmountsOptional() {
        var missing=new ApiDtos.CreateBillRequest(null,List.of(),null);
        var blankConcept=new ApiDtos.CreateTaxConceptRequest(" "," ",null,null," ");
        var zeroPayment=new ApiDtos.ElectronicPaymentRequest(1L,PaymentMethod.CARD,BigDecimal.ZERO);
        var negativePayment=new ApiDtos.ElectronicPaymentRequest(1L,PaymentMethod.CARD,new BigDecimal("-1"));
        var invalidPeriod=new ApiDtos.LiquidationRequest(1L,2L,"10/2026",BigDecimal.ZERO,LocalDate.now());
        var validFixedConfiguration=new ApiDtos.CreateTaxConfigurationRequest(1L,CalculationType.FIXED,
            null,BigDecimal.TEN,null,null,true,true,LocalDate.now(),null);

        assertThat(validator.validate(missing)).extracting(error->error.getPropertyPath().toString())
            .contains("taxpayerId","debtIds","dueDate");
        assertThat(validator.validate(blankConcept)).extracting(error->error.getPropertyPath().toString())
            .contains("code","name","originModule");
        assertThat(validator.validate(zeroPayment)).extracting(error->error.getPropertyPath().toString())
            .contains("amount");
        assertThat(validator.validate(negativePayment)).extracting(error->error.getPropertyPath().toString())
            .contains("amount");
        assertThat(validator.validate(invalidPeriod)).extracting(error->error.getPropertyPath().toString())
            .contains("period");
        assertThat(validator.validate(validFixedConfiguration)).isEmpty();
    }

    @Test void validatesDocumentsAndGenericEventPayloadCascades() {
        var exemption=new ApiDtos.CreateExemptionRequest(1L,2L,"Solicitud",new BigDecimal("50"),
            LocalDate.now(),LocalDate.now().plusDays(1),List.of(new ApiDtos.SubmitDocumentationRequest(" ","DNI",null)));
        var envelope=new ApiDtos.EventEnvelope<>(UUID.randomUUID(),"ticketUpdated",OffsetDateTime.now(),"M2",
            new ApiDtos.TicketEventData(null,null,null,null,null,null));
        var invalidBenefits=new ConfirmedInboundEvents.M8SocialBenefitData(UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Programa",UUID.randomUUID()," ",
            "Exención",null,"APPROVED",null,
            LocalDate.now(),null,OffsetDateTime.now());

        Set<String> exemptionPaths=validator.validate(exemption).stream()
            .map(error->error.getPropertyPath().toString()).collect(Collectors.toSet());
        Set<String> eventPaths=validator.validate(envelope).stream()
            .map(error->error.getPropertyPath().toString()).collect(Collectors.toSet());
        Set<String> benefitPaths=validator.validate(invalidBenefits).stream()
            .map(error->error.getPropertyPath().toString()).collect(Collectors.toSet());

        assertThat(exemptionPaths).contains("documents[0].externalDocumentId");
        assertThat(eventPaths).contains("data.ticketId");
        assertThat(benefitPaths).contains("type");
    }

    @Test void patchDtosKeepOmittedFieldsOptionalButValidateProvidedValues() {
        var taxPatch=new ApiDtos.UpdateTaxConfigurationRequest(null,null,null,null,null,null,null,null,null);
        var planPatch=new ApiDtos.UpdatePaymentPlanConfigurationRequest(null,null,null,null,null,null,null,null,null,null,null,null);
        var invalidPlanPatch=new ApiDtos.UpdatePaymentPlanConfigurationRequest(null,null,new BigDecimal("100.01"),
            null,null,null,null,null,null,null,null,null);

        assertThat(validator.validate(taxPatch)).isEmpty();
        assertThat(validator.validate(planPatch)).isEmpty();
        assertThat(validator.validate(invalidPlanPatch)).extracting(error->error.getPropertyPath().toString())
            .contains("minimumDownPaymentPercentage");
    }

    @Test void invalidNestedPaymentReturnsSafe400AndPersistsNothing() throws Exception {
        long before=payments.count();
        mvc.perform(post("/api/v1/payments")
                .header("X-Demo-Session",demo.token(DemoRole.CASHIER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taxpayerId\":1,\"paymentMethod\":\"CASH\",\"amount\":10,\"allocations\":[{\"debtId\":1,\"amount\":0}] }"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details.fieldErrors[0].field").value("allocations[0].amount"));
        assertThat(payments.count()).isEqualTo(before);
    }

    @Test void rejectsMoneyWithMoreThanTwoSignificantDecimalsBeforePersistence() throws Exception {
        var invalidPayment=new ApiDtos.RegisterPaymentRequest(1L,PaymentMethod.CASH,new BigDecimal("100.005"),null);
        var equivalentCents=new ApiDtos.RegisterPaymentRequest(1L,PaymentMethod.CASH,new BigDecimal("10.2300"),null);
        assertThat(validator.validate(invalidPayment)).extracting(error->error.getPropertyPath().toString())
            .contains("amount");
        assertThat(validator.validate(equivalentCents)).isEmpty();
        assertThat(validator.validate(new ApiDtos.CreateAdjustmentRequest(1L,AdjustmentType.DISCOUNT,
            new BigDecimal("10.555"),"Prueba"))).extracting(error->error.getPropertyPath().toString())
            .contains("amount");
        assertThat(validator.validate(new ApiDtos.LiquidationRequest(1L,1L,"2026-11",
            new BigDecimal("1234.567"),LocalDate.now()))).extracting(error->error.getPropertyPath().toString())
            .contains("taxableBase");
        assertThat(validator.validate(new ApiDtos.CreateTaxConfigurationRequest(1L,CalculationType.FIXED,
            null,new BigDecimal("100.005"),null,null,true,true,LocalDate.now(),null)))
            .extracting(error->error.getPropertyPath().toString()).contains("fixedAmount");
        assertThat(validator.validate(new ApiDtos.UpdateTaxConfigurationRequest(null,null,null,
            new BigDecimal("1.001"),null,null,null,null,null)))
            .extracting(error->error.getPropertyPath().toString()).contains("minimumAmount");
        assertThat(validator.validate(new ApiDtos.RegisterPaymentRequest(1L,PaymentMethod.CASH,BigDecimal.TEN,
            List.of(new ApiDtos.AllocationRequest(1L,new BigDecimal("1.001"))))))
            .extracting(error->error.getPropertyPath().toString()).contains("allocations[0].amount");
        assertThat(validator.validate(new ApiDtos.ElectronicPaymentRequest(1L,PaymentMethod.CARD,
            new BigDecimal("1.001")))).extracting(error->error.getPropertyPath().toString()).contains("amount");
        assertThat(validator.validate(new ApiDtos.ApplyCreditBalanceRequest(1L,new BigDecimal("1.001"))))
            .extracting(error->error.getPropertyPath().toString()).contains("amount");
        assertThat(validator.validate(new ApiDtos.GrantPaymentPlanRequest(new BigDecimal("1.001"))))
            .extracting(error->error.getPropertyPath().toString()).contains("downPaymentAmount");
        var invalidRun=new ApiDtos.CreateLiquidationRunRequest(1L,"2026-11",LocalDate.now(),
            List.of(new ApiDtos.LiquidationRunItemRequest(1L,new BigDecimal("1.001"))));
        assertThat(validator.validate(invalidRun)).extracting(error->error.getPropertyPath().toString())
            .contains("items[0].taxableBase");

        long before=payments.count();
        mvc.perform(post("/api/v1/payments")
                .header("X-Demo-Session",demo.token(DemoRole.CASHIER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taxpayerId\":1,\"paymentMethod\":\"CASH\",\"amount\":100.005}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details.fieldErrors[0].field").value("amount"))
            .andExpect(jsonPath("$.details.fieldErrors[0].messages[0]").value("Debe tener como máximo 2 decimales"));
        assertThat(payments.count()).isEqualTo(before);
    }

    @Test void validatesMoneyScaleOnInboundModuleContractsWithoutChangingTheirShapes() {
        var external=new ApiDtos.ExternalObligationData("M7-1",TaxpayerType.CITIZEN,"citizen-1",
            new BigDecimal("12.345"),LocalDate.now());
        var legacyInfraction=new ApiDtos.InfractionData("M7-2",TaxpayerType.CITIZEN,"citizen-1",
            new BigDecimal("12.345"),LocalDate.now());
        assertThat(validator.validate(external)).extracting(error->error.getPropertyPath().toString())
            .contains("amount");
        assertThat(validator.validate(legacyInfraction)).extracting(error->error.getPropertyPath().toString())
            .contains("amount");
        assertThat(validator.validate(new ApiDtos.ExternalObligationData("M7-1",TaxpayerType.CITIZEN,"citizen-1",
            new BigDecimal("12.3400"),LocalDate.now()))).isEmpty();

        var m7Data=new ConfirmedInboundEvents.M7InfractionData(UUID.randomUUID(),"citizen-1",
            ConfirmedInboundEvents.M7DebtorIdType.DNI,"AA111AA","TEST",OffsetDateTime.now(),
            new BigDecimal("12.345"),List.of(),new BigDecimal("12.34"),"INS-1",
            new ConfirmedInboundEvents.M7Location("Calle","Esquina",new BigDecimal("-34.60"),new BigDecimal("-58.40")));
        var m7Event=new ConfirmedInboundEvents.M7InfractionConfirmedEvent(UUID.randomUUID(),"infractionConfirmed",
            "1.0",OffsetDateTime.now(),"transito",UUID.randomUUID(),null,m7Data);
        assertThat(validator.validate(m7Event)).extracting(error->error.getPropertyPath().toString())
            .contains("data.baseAmount");

        var m4Data=new CoreM4Events.PermitFeeGeneratedData("FEE-1","PA-1","EST-1","30712345678",
            new BigDecimal("12.345"),"ARS");
        var m4Event=new CoreM4Events.Envelope<>(UUID.randomUUID(),"permitFeeGenerated","1.0",OffsetDateTime.now(),
            "habilitaciones",UUID.randomUUID(),null,m4Data);
        assertThat(validator.validate(m4Event)).extracting(error->error.getPropertyPath().toString())
            .contains("data.amount");

        var preliminary=new PreliminaryM4Events.Envelope<>("M4","permitFeeGenerated",
            new PreliminaryM4Events.PermitFeeGeneratedData("FEE-2","PA-2","EST-2",new BigDecimal("12.345")));
        assertThat(validator.validate(preliminary)).extracting(error->error.getPropertyPath().toString())
            .contains("data.amount");

        var m8Data=new ConfirmedInboundEvents.M8SocialBenefitData(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),"Programa",UUID.randomUUID(),"Asignación","Beneficio",
            new BigDecimal("12.345"),"APPROVED",null,LocalDate.now(),null,OffsetDateTime.now());
        assertThat(validator.validate(m8Data)).extracting(error->error.getPropertyPath().toString())
            .contains("amount");
    }

    @Test void invalidLiquidationFormatReturns400BeforePersistence() throws Exception {
        long before=liquidations.count();
        mvc.perform(post("/api/v1/liquidations")
                .header("X-Demo-Session",demo.token(DemoRole.RENTAS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taxpayerId\":1,\"taxConceptId\":1,\"period\":\"10/2026\",\"taxableBase\":0,\"dueDate\":\"2026-10-22\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details.fieldErrors[0].field").value("period"));
        assertThat(liquidations.count()).isEqualTo(before);
    }

    @Test void adjustmentWithFractionalCentsReturns400BeforePersistence() throws Exception {
        long before=adjustments.count();
        mvc.perform(post("/api/v1/adjustments")
                .header("X-Demo-Session",demo.token(DemoRole.RENTAS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"debtId\":1,\"type\":\"DISCOUNT\",\"amount\":10.005,\"reason\":\"Test\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details.fieldErrors[0].field").value("amount"));
        assertThat(adjustments.count()).isEqualTo(before);
    }

    @Test void liquidationRunWithFractionalCentBaseReturns400BeforePersistence() throws Exception {
        long before=liquidationRuns.count();
        mvc.perform(post("/api/v1/liquidation-runs")
                .header("X-Demo-Session",demo.token(DemoRole.RENTAS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taxConceptId\":1,\"period\":\"2026-11\",\"dueDate\":\"2026-11-30\",\"items\":[{\"taxpayerId\":1,\"taxableBase\":10.005}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details.fieldErrors[0].field").value("items[0].taxableBase"));
        assertThat(liquidationRuns.count()).isEqualTo(before);
    }

    @Test void individualLiquidationWithFractionalCentBaseReturns400BeforePersistence() throws Exception {
        LocalDate today=LocalDate.now();
        String period=java.time.YearMonth.from(today).toString();
        ApiDtos.LiquidationRequest request=new ApiDtos.LiquidationRequest(1L,1L,period,
            new BigDecimal("100.005"),today);
        assertThat(validator.validate(request)).extracting(error->error.getPropertyPath().toString())
            .containsExactly("taxableBase");

        long before=liquidations.count();
        mvc.perform(post("/api/v1/liquidations")
                .header("X-Demo-Session",demo.token(DemoRole.RENTAS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taxpayerId\":1,\"taxConceptId\":1,\"period\":\""+period
                    +"\",\"taxableBase\":100.005,\"dueDate\":\""+today+"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details.fieldErrors[0].field").value("taxableBase"))
            .andExpect(jsonPath("$.details.fieldErrors[0].messages[0]").value("Debe tener como máximo 2 decimales"));
        assertThat(liquidations.count()).isEqualTo(before);
    }

    @Test void optionalBodiesWithConstraintsAreValidatedWhenPresent() throws Exception {
        mvc.perform(post("/api/v1/payment-plan-requests/999999/grant")
                .header("X-Demo-Session",demo.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"downPaymentAmount\":-1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/exemption-requests/999999/approve")
                .header("X-Demo-Session",demo.token(DemoRole.SUPERVISOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"percentage\":100.01}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test void partialPlanConfigurationPatchRemainsValidOverHttp() throws Exception {
        PaymentPlanConfiguration configuration=new PaymentPlanConfiguration();
        configuration.version=planConfigurations.findAll().stream().mapToInt(value->value.version).max().orElse(0)+1;
        configuration.minimumInstallments=1;
        configuration.maximumInstallments=12;
        configuration.minimumDownPaymentPercentage=BigDecimal.ZERO;
        configuration.interestRate=BigDecimal.ONE;
        configuration.graceDays=0;
        configuration.maxOverdueInstallments=1;
        configuration.maxRefinancingCount=0;
        configuration.validFrom=LocalDate.now().minusDays(1);
        configuration.active=true;
        configuration.createdBy="qa";
        configuration.createdAt=OffsetDateTime.now();
        planConfigurations.saveAndFlush(configuration);

        mvc.perform(patch("/api/v1/payment-plan-configurations/{id}",configuration.id)
                .header("X-Demo-Session",demo.token(DemoRole.RENTAS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"interestRate\":2.5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.interestRate").value(2.5));
    }
}
