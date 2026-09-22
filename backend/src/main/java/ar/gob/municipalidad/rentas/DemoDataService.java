package ar.gob.municipalidad.rentas;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Datos mínimos y repetibles para la demostración local; nunca forma parte del perfil productivo. */
@Service
@Profile("!prod")
@ConditionalOnProperty(name="rentas.security.dev-mode",havingValue="true")
class DemoDataService {
    private static final String EXTERNAL_TAXPAYER_ID="DEMO-TAXPAYER-001";
    private static final UUID TAXPAYER_EVENT_ID=UUID.nameUUIDFromBytes("M5-DEMO-TAXPAYER-001".getBytes(StandardCharsets.UTF_8));

    private final DemoAuthService auth;
    private final DemoUserRepository users;
    private final TaxpayerIntegrationService taxpayerIntegration;
    private final TaxConceptRepository concepts;
    private final TaxConfigurationRepository taxConfigurations;
    private final CatalogService catalog;
    private final LiquidationRepository liquidations;
    private final LiquidationService liquidationService;
    private final DebtRepository debts;
    private final PaymentPlanRepository plans;
    private final PaymentPlanDebtRepository planDebts;
    private final PaymentPlanConfigurationRepository planConfigurations;
    private final PlanWorkflowService planWorkflow;

    DemoDataService(DemoAuthService auth,DemoUserRepository users,TaxpayerIntegrationService taxpayerIntegration,
            TaxConceptRepository concepts,TaxConfigurationRepository taxConfigurations,CatalogService catalog,
            LiquidationRepository liquidations,LiquidationService liquidationService,DebtRepository debts,
            PaymentPlanRepository plans,PaymentPlanDebtRepository planDebts,
            PaymentPlanConfigurationRepository planConfigurations,PlanWorkflowService planWorkflow) {
        this.auth=auth;this.users=users;this.taxpayerIntegration=taxpayerIntegration;this.concepts=concepts;
        this.taxConfigurations=taxConfigurations;this.catalog=catalog;this.liquidations=liquidations;
        this.liquidationService=liquidationService;this.debts=debts;this.plans=plans;this.planDebts=planDebts;
        this.planConfigurations=planConfigurations;this.planWorkflow=planWorkflow;
    }

    @Transactional
    DemoAuthController.DemoDataResponse initialize(String password) {
        TaxpayerReference taxpayer=taxpayerIntegration.consume(new ApiDtos.EventEnvelope<>(TAXPAYER_EVENT_ID,"taxpayerCreated",
            OffsetDateTime.now(),"M1",new ApiDtos.TaxpayerEventData(TaxpayerType.CITIZEN,EXTERNAL_TAXPAYER_ID,
                "40123456","20-40123456-7","Contribuyente Demo",TaxpayerStatus.ACTIVE)));

        ensureUser("rentas.demo","Personal Rentas Demo",DemoRole.RENTAS,null,password);
        ensureUser("cajero.demo","Cajero Demo",DemoRole.CASHIER,null,password);
        ensureUser("contribuyente.demo","Contribuyente Demo",DemoRole.TAXPAYER,taxpayer.id,password);

        TaxConcept electronicConcept=ensureConcept("TASA_SERVICIOS","Tasa de servicios generales");
        TaxConcept planConcept=ensureConcept("ABL","Alumbrado, barrido y limpieza");
        ensureTaxConfiguration(electronicConcept,new BigDecimal("10000.00"));
        ensureTaxConfiguration(planConcept,new BigDecimal("15000.00"));
        PaymentPlanConfiguration planConfiguration=ensurePlanConfiguration();

        Debt debtA=ensureOpenDebt(taxpayer,electronicConcept,30);
        Debt debtB=ensureOpenDebt(taxpayer,planConcept,45);
        return new DemoAuthController.DemoDataResponse(taxpayer.id,debtA.id,debtB.id,planConfiguration.id,
            List.of("supervisor.demo","rentas.demo","cajero.demo","contribuyente.demo"));
    }

    private void ensureUser(String username,String displayName,DemoRole role,Long taxpayerId,String password) {
        users.findByUsernameIgnoreCase(username).ifPresentOrElse(existing->{
            CatalogService.require(existing.role==role,"DEMO_USER_ROLE_MISMATCH","El usuario DEMO existente tiene otro rol");
            CatalogService.require(java.util.Objects.equals(existing.taxpayerId,taxpayerId),"DEMO_USER_TAXPAYER_MISMATCH","El usuario DEMO existente está asociado a otro contribuyente");
        },()->auth.create(new DemoAuthController.CreateUserRequest(username,password,displayName,role,taxpayerId)));
    }

    private TaxConcept ensureConcept(String code,String name) {
        return concepts.findByCode(code).orElseGet(()->catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(code,name,
            "Concepto municipal para demostración local",TaxConceptType.FEE,"M5")));
    }

    private TaxConfiguration ensureTaxConfiguration(TaxConcept concept,BigDecimal amount) {
        LocalDate today=LocalDate.now();
        return taxConfigurations.findAll().stream()
            .filter(x->x.taxConceptId.equals(concept.id)&&x.status==TaxConfigurationStatus.ACTIVE&&!x.validFrom.isAfter(today)&&(x.validUntil==null||!x.validUntil.isBefore(today)))
            .max(java.util.Comparator.comparingInt(x->x.version)).orElseGet(()->{
                TaxConfiguration created=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,
                    CalculationType.FIXED,null,amount,null,null,true,true,today,null));
                catalog.submit(created.id);return catalog.approve(created.id);
            });
    }

    private PaymentPlanConfiguration ensurePlanConfiguration() {
        LocalDate today=LocalDate.now();
        return planConfigurations.findAll().stream()
            .filter(x->x.active&&x.minimumInstallments<=3&&x.maximumInstallments>=12&&!x.validFrom.isAfter(today)&&(x.validUntil==null||!x.validUntil.isBefore(today)))
            .max(java.util.Comparator.comparingInt(x->x.version)).orElseGet(()->planWorkflow.createConfiguration(
                new ApiDtos.CreatePaymentPlanConfigurationRequest(3,12,BigDecimal.ZERO,new BigDecimal("12.00"),0,2,true,true,1,today,null,true)));
    }

    private Debt ensureOpenDebt(TaxpayerReference taxpayer,TaxConcept concept,int dueDays) {
        return debts.findByTaxpayerId(taxpayer.id).stream()
            .filter(x->x.taxConceptId.equals(concept.id)&&x.status!=DebtStatus.PAID&&x.status!=DebtStatus.CANCELLED&&x.outstandingBalance.signum()>0)
            .filter(x->!plans.existsByDebtIdAndStatus(x.id,PaymentPlanStatus.ACTIVE)&&!planDebts.existsByDebtIdAndStatus(x.id,PaymentPlanDebtStatus.ACTIVE))
            .findFirst().orElseGet(()->createDebt(taxpayer,concept,dueDays));
    }

    private Debt createDebt(TaxpayerReference taxpayer,TaxConcept concept,int dueDays) {
        YearMonth period=YearMonth.now();
        while(liquidations.existsByTaxpayerIdAndTaxConceptIdAndPeriod(taxpayer.id,concept.id,period.toString())) period=period.plusMonths(1);
        Liquidation liquidation=liquidationService.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,period.toString(),BigDecimal.ZERO,LocalDate.now().plusDays(dueDays)));
        return debts.findByLiquidationId(liquidation.id).orElseThrow();
    }
}
