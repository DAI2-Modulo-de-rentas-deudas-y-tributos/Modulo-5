package ar.gob.municipalidad.rentas;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(properties={"spring.jpa.properties.hibernate.generate_statistics=true","spring.jpa.properties.hibernate.session.events.log=false"})
@Transactional
class ApiPerformanceTests {
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;
    @Autowired TaxConfigurationRepository configurations;
    @Autowired LiquidationRepository liquidations;
    @Autowired LiquidationComponentRepository components;
    @Autowired DebtRepository debts;
    @Autowired PaymentPlanDebtRepository planDebts;
    @Autowired PaymentPlanRepository plans;
    @Autowired BillRepository bills;
    @Autowired BillDebtRepository billDebts;
    @Autowired LiquidationService liquidationService;
    @Autowired ApiResponseService responses;
    @Autowired IndicatorService indicators;
    @Autowired PlanWorkflowService planWorkflow;

    @Test void listResponsesBatchChildrenAndPlanMembership() {
        String suffix=UUID.randomUUID().toString();
        TaxpayerReference taxpayer=new TaxpayerReference();taxpayer.taxpayerType=TaxpayerType.CITIZEN;taxpayer.externalId="PERF-"+suffix;taxpayer.dni=suffix;taxpayer.displayName="Performance";taxpayer.externalStatus=TaxpayerStatus.ACTIVE;taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();
        TaxConcept concept=new TaxConcept();concept.code="PERF_"+suffix;concept.name="Performance";concept.type=TaxConceptType.FEE;concept.originModule="M5";concept.active=true;concept.createdAt=concept.updatedAt=OffsetDateTime.now();
        TaxConfiguration configuration=new TaxConfiguration();configuration.taxConceptId=null;configuration.version=1;configuration.calculationType=CalculationType.FIXED;configuration.fixedAmount=money(10);configuration.partialPaymentAllowed=true;configuration.paymentPlanAllowed=true;configuration.validFrom=LocalDate.now().minusDays(1);configuration.status=TaxConfigurationStatus.ACTIVE;configuration.createdBy="test";configuration.createdAt=configuration.approvedAt=OffsetDateTime.now();configuration.approvedBy="test";
        taxpayer=taxpayers.save(taxpayer);concept=concepts.save(concept);configuration.taxConceptId=concept.id;configuration=configurations.save(configuration);
        List<Liquidation> liquidationPage=new ArrayList<>();
        for(int n=0;n<5;n++){Liquidation l=liquidation(n,taxpayer.id,concept.id,configuration.id);liquidations.save(l);liquidationPage.add(l);for(int c=0;c<2;c++){LiquidationComponent component=new LiquidationComponent();component.liquidationId=l.id;component.type=LiquidationComponentType.BASE;component.sourceType="TaxConfiguration";component.sourceId=configuration.id.toString();component.description="Componente";component.amount=money(10);components.save(component);}}
        List<Debt> debtPage=new ArrayList<>();for(int n=0;n<5;n++){Debt d=debt(taxpayer.id,concept.id,liquidationPage.get(n).id);debts.save(d);debtPage.add(d);}
        PaymentPlan plan=new PaymentPlan();plan.taxpayerId=taxpayer.id;plan.originalPrincipalAmount=plan.financedPrincipalAmount=plan.totalPlanAmount=plan.outstandingPlanAmount=money(10);plan.downPaymentAmount=plan.financingInterestAmount=plan.paidAmount=money(0);plan.installmentCount=1;plan.status=PaymentPlanStatus.ACTIVE;plan.grantedBy="test";plan.grantedAt=OffsetDateTime.now();plans.save(plan);
        PaymentPlanDebt active=new PaymentPlanDebt();active.paymentPlanId=plan.id;active.debtId=debtPage.get(0).id;active.includedPrincipalAmount=active.remainingPrincipalAmount=money(10);active.principalPaidAmount=money(0);active.status=PaymentPlanDebtStatus.ACTIVE;active.createdAt=OffsetDateTime.now();planDebts.save(active);
        List<Bill> billPage=new ArrayList<>();for(int n=0;n<4;n++){Bill bill=bill(n,taxpayer.id);bills.save(bill);billPage.add(bill);for(int d=0;d<2;d++){BillDebt link=new BillDebt();link.billId=bill.id;link.debtId=debtPage.get((n+d)%debtPage.size()).id;link.amountAtIssue=money(10);billDebts.save(link);}}

        Statistics statistics=statistics();statistics.clear();
        var liquidationResponses=liquidationService.responses(new PageImpl<>(liquidationPage));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);assertThat(liquidationResponses).allSatisfy(x->assertThat(x.components()).hasSize(2));

        statistics.clear();var debtResponses=responses.debts(new PageImpl<>(debtPage));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);assertThat(debtResponses.getContent().get(0).inPaymentPlan()).isTrue();assertThat(debtResponses.getContent().subList(1,5)).allMatch(x->!x.inPaymentPlan());

        statistics.clear();var billResponses=responses.bills(new PageImpl<>(billPage));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);assertThat(billResponses).allSatisfy(x->assertThat(x.debts()).hasSize(2));
    }

    @Test void indicatorsAndDefaultedPlansUseBoundedDatabaseQueries() {
        Statistics statistics=statistics();statistics.clear();
        indicators.summary(null,null);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);

        statistics.clear();planWorkflow.defaulted(PageRequest.of(0,20));
        assertThat(statistics.getPrepareStatementCount()).isBetween(1L,2L);
    }

    private Statistics statistics(){return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();}
    private BigDecimal money(int value){return BigDecimal.valueOf(value).setScale(2);}
    private Liquidation liquidation(int n,Long taxpayerId,Long conceptId,Long configurationId){Liquidation l=new Liquidation();l.taxpayerId=taxpayerId;l.taxConceptId=conceptId;l.taxConfigurationId=configurationId;l.configurationVersion=1;l.period="2026-"+String.format("%02d",n+1);l.taxableBase=l.baseAmount=l.finalAmount=money(10);l.discountAmount=l.exemptionAmount=l.surchargeAmount=l.interestAmount=money(0);l.dueDate=LocalDate.now().plusDays(1);l.status="ISSUED";l.createdBy="test";l.issuedAt=OffsetDateTime.now();return l;}
    private Debt debt(Long taxpayerId,Long conceptId,Long liquidationId){Debt d=new Debt();d.taxpayerId=taxpayerId;d.taxConceptId=conceptId;d.originType=DebtOriginType.LIQUIDATION;d.liquidationId=liquidationId;d.originalAmount=d.currentAmount=d.outstandingBalance=money(10);d.dueDate=LocalDate.now().plusDays(1);d.status=DebtStatus.PENDING;d.createdAt=d.updatedAt=OffsetDateTime.now();return d;}
    private Bill bill(int n,Long taxpayerId){Bill b=new Bill();b.number="PERF-"+UUID.randomUUID()+"-"+n;b.taxpayerId=taxpayerId;b.totalAmount=money(20);b.issueDate=LocalDate.now();b.dueDate=LocalDate.now().plusDays(1);b.status=BillStatus.ISSUED;b.createdBy="test";b.createdAt=OffsetDateTime.now();return b;}
}
