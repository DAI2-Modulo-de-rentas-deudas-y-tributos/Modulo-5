package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest @ActiveProfiles("test")
class SegregationOfDutiesTests {
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired AdjustmentService adjustments;
    @Autowired LiquidationRunService runs;
    @Autowired ExemptionService exemptions;
    @Autowired SocialBenefitRuleService socialRules;
    @Autowired PlanWorkflowService plans;
    @Autowired ReversalService reversals;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;
    @Autowired TaxConfigurationRepository configurations;
    @Autowired DebtRepository debts;
    @Autowired AdjustmentRepository adjustmentRequests;
    @Autowired LiquidationRunRepository liquidationRuns;
    @Autowired LiquidationRepository liquidationRecords;
    @Autowired ExemptionRequestRepository exemptionRequests;
    @Autowired ExemptionRepository exemptionRecords;
    @Autowired SocialBenefitRuleRepository socialRuleRecords;
    @Autowired PaymentPlanRequestRepository planRequests;
    @Autowired PaymentPlanRepository paymentPlans;
    @Autowired PlanExpirationRepository expirations;
    @Autowired RefinancingRequestRepository refinancings;
    @Autowired PaymentRepository payments;
    @Autowired PaymentReversalRepository reversalRequests;
    @Autowired OutboxRepository outbox;
    @Autowired AuditRepository audit;

    @AfterEach void clearIdentity(){SecurityContextHolder.clearContext();}

    @Test void taxConfigurationCreatorAndSubmitterCannotApproveButAnotherSupervisorCan(){
        TaxConcept concept=concept("SOD-TAX-CONFIG");
        authenticate("config-maker","RENTAS");
        TaxConfiguration byCreator=catalog.createConfiguration(configurationRequest(concept.id));
        catalog.submit(byCreator.id);
        assertForbidden("TaxConfiguration",byCreator.id,()->catalog.approve(byCreator.id));
        assertThat(configurations.findById(byCreator.id).orElseThrow().status).isEqualTo(TaxConfigurationStatus.PENDING_APPROVAL);

        authenticate("config-creator-2","RENTAS");
        TaxConfiguration bySubmitter=catalog.createConfiguration(configurationRequest(concept.id));
        authenticate("config-submitter-2","RENTAS");
        catalog.submit(bySubmitter.id);
        assertForbidden("TaxConfiguration",bySubmitter.id,()->catalog.approve(bySubmitter.id));
        assertThat(configurations.findById(bySubmitter.id).orElseThrow().approvedAt).isNull();

        authenticate("config-supervisor","SUPERVISOR");
        assertThat(catalog.approve(byCreator.id).status).isEqualTo(TaxConfigurationStatus.ACTIVE);
        assertThat(catalog.approve(bySubmitter.id).status).isEqualTo(TaxConfigurationStatus.ACTIVE);
    }

    @Test void adjustmentSelfApprovalDoesNotChangeDebtOrPublishAndDifferentSupervisorCanApprove(){
        Scenario scenario=scenario("SOD-ADJUST");
        authenticate("adjustment-maker","RENTAS");
        AdjustmentRequest request=adjustments.create(new ApiDtos.CreateAdjustmentRequest(scenario.debt.id,AdjustmentType.DISCOUNT,new BigDecimal("10"),"Prueba segregación"));
        long eventCount=outbox.findAll().stream().filter(e->e.eventType.equals("debtAdjusted")).count();
        assertForbidden("AdjustmentRequest",request.id,()->adjustments.approve(request.id,"Aprobado"));
        assertThat(debts.findById(scenario.debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");
        assertThat(adjustmentRequests.findById(request.id).orElseThrow().status).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
        assertThat(outbox.findAll().stream().filter(e->e.eventType.equals("debtAdjusted")).count()).isEqualTo(eventCount);

        authenticate("adjustment-supervisor","SUPERVISOR");
        adjustments.approve(request.id,"Aprobado por otro usuario");
        assertThat(debts.findById(scenario.debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("90.00");
        assertThat(outbox.findAll().stream().filter(e->e.eventType.equals("debtAdjusted")).count()).isEqualTo(eventCount+1);
    }

    @Test void liquidationRunSubmitterCannotApproveAndOtherSupervisorCan(){
        Scenario scenario=scenario("SOD-RUN");
        authenticate("run-maker","RENTAS");
        LiquidationRun run=runs.create(new ApiDtos.CreateLiquidationRunRequest(scenario.concept.id,YearMonth.now().toString(),LocalDate.now().plusDays(20),List.of(new ApiDtos.LiquidationRunItemRequest(scenario.taxpayer.id,BigDecimal.ZERO))));
        runs.preview(run.id);
        authenticate("run-submitter","RENTAS");
        runs.submit(run.id);
        long liquidationCount=liquidationRecords.count();
        assertForbidden("LiquidationRun",run.id,()->runs.approve(run.id,null));
        assertThat(liquidationRuns.findById(run.id).orElseThrow().status).isEqualTo(LiquidationRunStatus.PENDING_APPROVAL);
        assertThat(liquidationRecords.count()).isEqualTo(liquidationCount);
        authenticate("run-supervisor","SUPERVISOR");
        assertThat(runs.approve(run.id,null).status).isEqualTo(LiquidationRunStatus.APPROVED);
    }

    @Test void exemptionRequesterAndResolutionSubmitterCannotApprove(){
        Scenario scenario=scenario("SOD-EXEMPTION");
        authenticate("exemption-requester","RENTAS");
        ExemptionRequest request=exemptions.create(new ApiDtos.CreateExemptionRequest(scenario.taxpayer.id,scenario.concept.id,"Solicitud de prueba",new BigDecimal("50"),LocalDate.now(),LocalDate.now().plusMonths(3)));
        authenticate("exemption-reviewer","RENTAS");
        exemptions.start(request.id);
        authenticate("exemption-submitter","RENTAS");
        exemptions.submit(request.id);
        long outboxCount=outbox.count();
        assertForbidden("ExemptionRequest",request.id,()->exemptions.approve(request.id,null));
        assertThat(exemptionRequests.findById(request.id).orElseThrow().status).isEqualTo(ExemptionRequestStatus.PENDING_RESOLUTION);
        assertThat(exemptionRecords.findByRequestId(request.id)).isEmpty();
        assertThat(outbox.count()).isEqualTo(outboxCount);
        authenticate("exemption-supervisor","SUPERVISOR");
        exemptions.approve(request.id,null);
        assertThat(exemptionRequests.findById(request.id).orElseThrow().status).isEqualTo(ExemptionRequestStatus.APPROVED);
        assertThat(exemptionRecords.findByRequestId(request.id)).isPresent();
    }

    @Test void socialRuleProposerAndSubmitterCannotApprove(){
        TaxConcept concept=concept("SOD-SOCIAL");
        UUID program=UUID.randomUUID();
        authenticate("social-proposer","RENTAS");
        SocialBenefitRuleService.RuleProposal input=new SocialBenefitRuleService.RuleProposal(program,concept.code,new BigDecimal("10"),LocalDate.now(),null);
        SocialBenefitRule rule=socialRules.propose(input);
        authenticate("social-submitter","RENTAS","SUPERVISOR");
        socialRules.submit(rule.id);
        assertForbidden("SocialBenefitRule",rule.id,()->socialRules.approve(rule.id));
        assertThat(socialRuleRecords.findById(rule.id).orElseThrow().status).isEqualTo(SocialBenefitRuleStatus.PENDING_APPROVAL);
        authenticate("social-supervisor","SUPERVISOR");
        assertThat(socialRules.approve(rule.id).status).isEqualTo(SocialBenefitRuleStatus.APPROVED);
    }

    @Test void paymentPlanRequesterCannotGrantOrApproveItsOwnException(){
        Scenario scenario=scenario("SOD-PLAN");
        planConfiguration(1,12);
        authenticate("plan-taxpayer",scenario.taxpayer.id,"TAXPAYER");
        PaymentPlanRequest request=plans.request(new ApiDtos.CreatePaymentPlanRequest(scenario.taxpayer.id,List.of(scenario.debt.id),2));
        long eventCount=outbox.count();
        long plansBefore=paymentPlans.count();
        assertForbidden("PaymentPlanRequest",request.id,()->plans.grant(request.id,null));
        assertThat(planRequests.findById(request.id).orElseThrow().status).isEqualTo(PaymentPlanRequestStatus.PENDING);
        assertThat(paymentPlans.count()).isEqualTo(plansBefore);
        assertThat(outbox.count()).isEqualTo(eventCount);
        authenticate("plan-granter","RENTAS");
        plans.grant(request.id,null);
        assertThat(planRequests.findById(request.id).orElseThrow().status).isEqualTo(PaymentPlanRequestStatus.GRANTED);

        Scenario exceptionalDebt=scenario("SOD-PLAN-EXCEPTION");
        planConfiguration(1,2);
        authenticate("exception-taxpayer",exceptionalDebt.taxpayer.id,"TAXPAYER");
        PaymentPlanRequest exceptional=plans.request(new ApiDtos.CreatePaymentPlanRequest(exceptionalDebt.taxpayer.id,List.of(exceptionalDebt.debt.id),3));
        assertThat(exceptional.exceptional).isTrue();
        authenticate("exception-submitter",exceptionalDebt.taxpayer.id,"TAXPAYER");
        plans.submitException(exceptional.id,new ApiDtos.SubmitPlanExceptionRequest("Excepción de prueba"));
        assertForbidden("PaymentPlanRequest",exceptional.id,()->plans.approveException(exceptional.id,null));
        authenticate("exception-supervisor","SUPERVISOR");
        assertThat(plans.approveException(exceptional.id,null).exceptionApproved).isTrue();
    }

    @Test void expirationRequesterCannotApprovePlanExpiration(){
        Scenario scenario=scenario("SOD-EXPIRATION");
        planConfiguration(1,12);
        PaymentPlan plan=grantPlan(scenario);
        PlanExpirationRequest expiration=new PlanExpirationRequest();expiration.paymentPlanId=plan.id;expiration.reason="Prueba";expiration.status=PlanExpirationStatus.PENDING_APPROVAL;expiration.requestedBy="expiration-requester";expiration.requestedAt=OffsetDateTime.now();expirations.save(expiration);
        long eventCount=outbox.count();
        authenticate("expiration-requester","SUPERVISOR");
        assertForbidden("PlanExpirationRequest",expiration.id,()->plans.approveExpiration(expiration.id,"Aprobación"));
        assertThat(paymentPlans.findById(plan.id).orElseThrow().status).isEqualTo(PaymentPlanStatus.ACTIVE);
        assertThat(outbox.count()).isEqualTo(eventCount);
        authenticate("expiration-supervisor","SUPERVISOR");
        assertThat(plans.approveExpiration(expiration.id,"Aprobación por otro").status).isEqualTo(PlanExpirationStatus.APPROVED);
        assertThat(paymentPlans.findById(plan.id).orElseThrow().status).isEqualTo(PaymentPlanStatus.EXPIRED);
    }

    @Test void refinancingRequesterAndExceptionSubmitterCannotApproveOrGrant(){
        Scenario scenario=scenario("SOD-REFINANCING");
        planConfiguration(1,12);
        PaymentPlan plan=grantPlan(scenario);
        RefinancingRequest requested=new RefinancingRequest();requested.originalPlanId=plan.id;requested.taxpayerId=scenario.taxpayer.id;requested.requestedInstallments=2;requested.outstandingPrincipalAtRequest=plan.originalPrincipalAmount;requested.estimatedInterest=BigDecimal.ZERO;requested.estimatedTotalAmount=plan.originalPrincipalAmount;requested.exceptionApproved=false;requested.status=RefinancingRequestStatus.PENDING;requested.requestedBy="refinancing-requester";requested.requestedAt=OffsetDateTime.now();refinancings.save(requested);
        authenticate("refinancing-requester","SUPERVISOR");
        assertForbidden("RefinancingRequest",requested.id,()->plans.grantRefinancing(requested.id));
        assertThat(paymentPlans.findById(plan.id).orElseThrow().status).isEqualTo(PaymentPlanStatus.ACTIVE);

        RefinancingRequest exceptional=new RefinancingRequest();exceptional.originalPlanId=plan.id;exceptional.taxpayerId=scenario.taxpayer.id;exceptional.requestedInstallments=2;exceptional.outstandingPrincipalAtRequest=plan.originalPrincipalAmount;exceptional.estimatedInterest=BigDecimal.ZERO;exceptional.estimatedTotalAmount=plan.originalPrincipalAmount;exceptional.exceptional=true;exceptional.status=RefinancingRequestStatus.PENDING_EXCEPTION_APPROVAL;exceptional.requestedBy="refinancing-requester";exceptional.exceptionSubmittedBy="refinancing-submitter";exceptional.requestedAt=OffsetDateTime.now();refinancings.save(exceptional);
        authenticate("refinancing-submitter","SUPERVISOR");
        assertForbidden("RefinancingRequest",exceptional.id,()->plans.approveRefinancingException(exceptional.id));
        authenticate("refinancing-supervisor","SUPERVISOR");
        assertThat(plans.approveRefinancingException(exceptional.id).exceptionApproved).isTrue();
    }

    @Test void reversalRequesterCannotApproveAndAnotherSupervisorCan(){
        Scenario scenario=scenario("SOD-REVERSAL");
        Payment payment=new Payment();payment.taxpayerId=scenario.taxpayer.id;payment.paymentMethod=PaymentMethod.CASH;payment.amount=new BigDecimal("10.00");payment.allocatedAmount=BigDecimal.ZERO.setScale(2);payment.unallocatedAmount=payment.amount;payment.status=PaymentStatus.CONFIRMED;payment.allocationStatus=PaymentAllocationStatus.UNALLOCATED;payment.origin=PaymentOrigin.CASHIER;payment.receiptNumber="SOD-REC-"+UUID.randomUUID();payment.registeredBy="payment-maker";payment.paidAt=payment.createdAt=OffsetDateTime.now();payments.save(payment);
        authenticate("reversal-requester","RENTAS");
        PaymentReversalRequest request=reversals.request(payment.id,"Prueba segregación");
        long eventCount=outbox.count();
        assertForbidden("PaymentReversal",request.id,()->reversals.approve(request.id));
        assertThat(reversalRequests.findById(request.id).orElseThrow().status).isEqualTo(PaymentReversalStatus.PENDING_APPROVAL);
        assertThat(payments.findById(payment.id).orElseThrow().status).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(outbox.count()).isEqualTo(eventCount);
        authenticate("reversal-supervisor","SUPERVISOR");
        assertThat(reversals.approve(request.id).status).isEqualTo(PaymentReversalStatus.APPROVED);
    }

    private Scenario scenario(String suffix){
        authenticate("fixture-"+suffix,"RENTAS");
        TaxpayerReference taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,suffix+"-"+UUID.randomUUID(),"123",null,"Persona "+suffix));
        TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(suffix+"-"+UUID.randomUUID(),suffix,null,TaxConceptType.FEE,"M5"));
        TaxConfiguration configuration=catalog.createConfiguration(configurationRequest(concept.id));catalog.submit(configuration.id);
        authenticate("fixture-supervisor-"+suffix,"SUPERVISOR");catalog.approve(configuration.id);
        authenticate("fixture-"+suffix,"RENTAS");
        liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));
        return new Scenario(taxpayer,concept,debts.findByTaxpayerId(taxpayer.id).get(0));
    }

    private PaymentPlan grantPlan(Scenario scenario){
        authenticate("plan-requester-"+UUID.randomUUID(),scenario.taxpayer.id,"TAXPAYER");
        PaymentPlanRequest request=plans.request(new ApiDtos.CreatePaymentPlanRequest(scenario.taxpayer.id,List.of(scenario.debt.id),2));
        authenticate("plan-granter-"+UUID.randomUUID(),"RENTAS");
        return paymentPlans.findById(plans.grant(request.id,null).paymentPlanId).orElseThrow();
    }

    private TaxConcept concept(String code){authenticate("fixture-"+code,"RENTAS");return catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(code+"-"+UUID.randomUUID(),code,null,TaxConceptType.FEE,"M5"));}
    private ApiDtos.CreateTaxConfigurationRequest configurationRequest(Long conceptId){return new ApiDtos.CreateTaxConfigurationRequest(conceptId,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null);}
    private void planConfiguration(int min,int max){authenticate("plan-config-"+UUID.randomUUID(),"RENTAS");plans.createConfiguration(new ApiDtos.CreatePaymentPlanConfigurationRequest(min,max,BigDecimal.ZERO,BigDecimal.ZERO,0,0,true,true,3,LocalDate.now().minusDays(1),null,true));}

    private void assertForbidden(String entityType,Long entityId,Runnable operation){
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,error->{assertThat(error.code).isEqualTo("SELF_APPROVAL_FORBIDDEN");assertThat(error.status).isEqualTo(403);});
        assertThat(audit.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(entityType,entityId.toString()))
            .anySatisfy(entry->{assertThat(entry.action).isEqualTo("SELF_APPROVAL_FORBIDDEN");assertThat(entry.userId).isEqualTo(SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof AuthenticatedIdentity identity?identity.userId():"unknown");});
    }

    private void authenticate(String user,String... roles){authenticate(user,null,roles);}
    private void authenticate(String user,Long taxpayerId,String... roles){
        var authorities=java.util.Arrays.stream(roles).map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(user,taxpayerId,UUID.nameUUIDFromBytes(user.getBytes())),null,authorities));
    }
    private record Scenario(TaxpayerReference taxpayer,TaxConcept concept,Debt debt){}
}
