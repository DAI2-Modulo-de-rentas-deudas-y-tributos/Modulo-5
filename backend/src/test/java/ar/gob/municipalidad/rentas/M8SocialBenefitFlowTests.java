package ar.gob.municipalidad.rentas;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest @ActiveProfiles("test") @Transactional
class M8SocialBenefitFlowTests {
    private static final UUID BENEFIT_ID=UUID.fromString("8b04513c-1e16-4a70-8882-93348cc3ddc0");
    private static final UUID CITIZEN_ID=UUID.fromString("ad1dc069-6020-44fc-98a3-17bbd32aa305");
    private static final UUID APPLICATION_ID=UUID.fromString("b3af8f4c-85fb-4f3b-8907-2b4b30ac9c4f");
    private static final UUID PROGRAM_ID=UUID.fromString("3d31d975-962e-43dd-bb72-ec60d803bde5");
    private static final UUID PROGRAM_EDITION_ID=UUID.fromString("9ce8be6d-5f4a-48ab-b16d-862c33c1bbd4");
    private static final UUID BENEFIT_TYPE_ID=UUID.fromString("8d1aac35-d761-4671-bbd6-caeeb237f498");
    private static final UUID SUPERVISOR_ID=UUID.fromString("638e1d46-9abb-4f44-9287-7367bb21d9ae");
    private static final OffsetDateTime FIRST_UPDATE=OffsetDateTime.parse("2026-09-01T12:00:00-03:00");

    @Autowired ConfirmedM8Consumer consumer;
    @Autowired SocialBenefitRepository benefits;
    @Autowired SocialBenefitEventHistoryRepository history;
    @Autowired SocialBenefitRuleService ruleService;
    @Autowired SocialBenefitRuleRepository rules;
    @Autowired CatalogService catalog;
    @Autowired LiquidationService liquidations;
    @Autowired LiquidationComponentRepository components;
    @Autowired LiquidationRepository liquidationRepository;
    @Autowired DebtRepository debts;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired EntityManager entityManager;

    @BeforeEach void authenticate(){as("rentas.qa","RENTAS",UUID.fromString("0796688a-12fc-46b2-b8fc-9003e22d7fd2"));}
    @AfterEach void clearIdentity(){SecurityContextHolder.clearContext();}

    @Test void officialV3UpdatesPersistThreeStatesNullableDatesAndTraceability(){
        String[] statuses={"APPROVED","SUSPENDED","FINALIZED"};
        for(int i=0;i<statuses.length;i++){
            String status=statuses[i];
            SocialBenefitReference received=consumer.consume(event(UUID.randomUUID(),status,FIRST_UPDATE.plusDays(i),
                i==0?LocalDate.of(2026,9,1):null,i==2?LocalDate.of(2027,1,15):i==0?LocalDate.of(2027,9,1):null,
                "Motivo "+status,"TAX_EXEMPTION"));
            entityManager.flush();entityManager.clear();
            SocialBenefitReference persisted=benefits.findByExternalBenefitId(BENEFIT_ID.toString()).orElseThrow();
            assertThat(persisted.id).isEqualTo(received.id);
            assertThat(persisted.externalCitizenId).isEqualTo(CITIZEN_ID.toString());
            assertThat(persisted.externalApplicationId).isEqualTo(APPLICATION_ID.toString());
            assertThat(persisted.externalProgramId).isEqualTo(PROGRAM_ID.toString());
            assertThat(persisted.externalStatus).isEqualTo(status);
            assertThat(persisted.externalReason).isEqualTo("Motivo "+status);
            assertThat(persisted.validFrom).isEqualTo(LocalDate.of(2026,9,1));
            assertThat(persisted.validUntil).isEqualTo(i==2?LocalDate.of(2027,1,15):LocalDate.of(2027,9,1));
            assertThat(persisted.benefitsPayload).contains(PROGRAM_EDITION_ID.toString(),BENEFIT_TYPE_ID.toString(),"TAX_EXEMPTION");
            assertThat(persisted.resolutionStatus).isEqualTo("PENDING_CITIZEN");
            assertThat(persisted.taxpayerId).isNull();
            assertThat(persisted.sourcePayloadHash).hasSize(64);
            assertThat(persisted.receivedAt).isNotNull();
        }
        assertThat(history.findByExternalBenefitIdOrderBySourceUpdatedAtAsc(BENEFIT_ID.toString()))
            .extracting(x->x.externalStatus).containsExactly(statuses);
    }

    @Test void duplicateAndOutOfOrderEventsDoNotOverwriteCurrentState(){
        var first=event(UUID.randomUUID(),"APPROVED",FIRST_UPDATE,LocalDate.of(2026,9,1),null,"Vigente","TAX_EXEMPTION");
        SocialBenefitReference original=consumer.consume(first);
        assertThat(consumer.consume(first).id).isEqualTo(original.id);
        var latest=event(UUID.randomUUID(),"SUSPENDED",FIRST_UPDATE.plusDays(2),null,null,"Pausado","TAX_EXEMPTION");
        consumer.consume(latest);
        consumer.consume(event(UUID.randomUUID(),"APPROVED",FIRST_UPDATE.plusDays(1),LocalDate.of(2026,9,1),null,"Atrasado","TAX_EXEMPTION"));
        consumer.consume(event(UUID.randomUUID(),"FINALIZED",FIRST_UPDATE.plusDays(2),null,LocalDate.of(2027,1,15),"Conflicto","TAX_EXEMPTION"));
        entityManager.flush();entityManager.clear();
        SocialBenefitReference persisted=benefits.findByExternalBenefitId(BENEFIT_ID.toString()).orElseThrow();
        assertThat(persisted.externalStatus).isEqualTo("SUSPENDED");
        assertThat(persisted.externalReason).isEqualTo("Pausado");
        assertThat(persisted.sourceEventId).isEqualTo(latest.eventId());
        assertThat(history.findByExternalBenefitIdOrderBySourceUpdatedAtAsc(BENEFIT_ID.toString()))
            .extracting(x->x.outcome).contains("APPLIED","STALE","CONFLICT");
        assertThat(benefits.count()).isEqualTo(1);
    }

    @Test void benefitWithoutCanonicalM1IdentityCannotDiscountEvenWithApprovedRule(){
        TaxpayerReference taxpayer=taxpayer(false);
        TaxConcept concept=concept("M8-NO-LINK");
        configuration(concept);
        approvedRule(concept,"100",LocalDate.of(2026,9,1),null);
        SocialBenefitReference benefit=consumer.consume(event(UUID.randomUUID(),"APPROVED",FIRST_UPDATE,
            LocalDate.of(2026,9,1),null,"Aprobado","TAX_EXEMPTION"));
        assertThat(benefit.taxpayerId).isNull();
        assertThat(benefit.resolutionStatus).isEqualTo("PENDING_CITIZEN");
        ApiDtos.LiquidationPreview preview=preview(taxpayer,concept,"2026-09");
        assertThat(preview.finalAmount()).isEqualByComparingTo("100.00");
        assertThat(preview.discountAmount()).isZero();
    }

    @Test void approvedRuleAppliesFullExemptionAndCreatesNoDebt(){
        TaxpayerReference taxpayer=taxpayer(true);
        TaxConcept target=concept("M8-TARGET");configuration(target);
        TaxConcept other=concept("M8-OTHER");configuration(other);
        SocialBenefitRule rule=approvedRule(target,"100",LocalDate.of(2026,9,1),null);
        SocialBenefitReference benefit=consumer.consume(event(UUID.randomUUID(),"APPROVED",FIRST_UPDATE,
            LocalDate.of(2026,9,1),null,"Aprobado","TAX_EXEMPTION"));
        assertThat(benefit.taxpayerId).isEqualTo(taxpayer.id);
        assertThat(preview(taxpayer,other,"2026-09").finalAmount()).isEqualByComparingTo("100.00");
        ApiDtos.LiquidationPreview preview=preview(taxpayer,target,"2026-09");
        assertThat(preview.baseAmount()).isEqualByComparingTo("100.00");
        assertThat(preview.discountAmount()).isEqualByComparingTo("100.00");
        assertThat(preview.finalAmount()).isZero();
        Liquidation issued=liquidations.create(request(taxpayer,target,"2026-09"));
        assertThat(issued.integrationId).isNotNull();
        assertThat(debts.findByLiquidationId(issued.id)).isEmpty();
        entityManager.flush();entityManager.clear();
        LiquidationComponent applied=components.findByLiquidationIdOrderById(issued.id).stream()
            .filter(x->x.type==LiquidationComponentType.SOCIAL_BENEFIT).findFirst().orElseThrow();
        assertThat(applied.sourceBenefitId).isEqualTo(BENEFIT_ID.toString());
        assertThat(applied.socialRuleId).isEqualTo(rule.id);
        assertThat(applied.socialRuleVersion).isEqualTo(rule.version);
        assertThat(liquidationRepository.findById(issued.id).orElseThrow().integrationId).isEqualTo(issued.integrationId);
    }

    @Test void twoApplicableBenefitsUseTheHigherRateWithoutAccumulating(){
        TaxpayerReference taxpayer=taxpayer(true);TaxConcept concept=concept("M8-MAX");configuration(concept);
        approvedRule(concept,"40",LocalDate.of(2026,9,1),null);
        consumer.consume(event(UUID.randomUUID(),"APPROVED",FIRST_UPDATE,LocalDate.of(2026,9,1),null,
            "Primer beneficio","TAX_EXEMPTION"));
        UUID secondProgram=UUID.fromString("82ec8864-430f-4300-8f6c-f83aa50aaeeb");
        UUID secondBenefit=UUID.fromString("33767af1-1443-49ac-9374-a2ec85937ce4");
        approvedRule(secondProgram,concept,"70",LocalDate.of(2026,9,1),null);
        var second=event(UUID.randomUUID(),"APPROVED",FIRST_UPDATE,LocalDate.of(2026,9,1),null,
            "Segundo beneficio","TAX_EXEMPTION");
        var data=second.data();
        consumer.consume(new ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent(second.eventId(),second.eventType(),
            second.eventVersion(),second.occurredAt(),second.sourceModule(),second.correlationId(),second.causationId(),
            new ConfirmedInboundEvents.M8SocialBenefitData(secondBenefit,data.citizenId(),data.applicationId(),
                secondProgram,data.programEditionId(),data.programName(),data.benefitTypeId(),data.type(),
                data.description(),data.amount(),data.status(),data.reason(),data.startDate(),
                data.endDate(),data.updatedAt())));
        ApiDtos.LiquidationPreview preview=preview(taxpayer,concept,"2026-09");
        assertThat(preview.discountAmount()).isEqualByComparingTo("70.00");
        assertThat(preview.finalAmount()).isEqualByComparingTo("30.00");
        assertThat(preview.components()).anyMatch(x->secondBenefit.toString().equals(x.sourceBenefitId()));
    }

    @Test void suspendedFinalizedAndNonTaxBenefitsNeverReduceLiquidation(){
        TaxpayerReference taxpayer=taxpayer(true);TaxConcept concept=concept("M8-STATES");configuration(concept);
        approvedRule(concept,"75",LocalDate.of(2026,9,1),null);
        consumer.consume(event(UUID.randomUUID(),"APPROVED",FIRST_UPDATE,LocalDate.of(2026,9,1),null,"Alimentos","FOOD_ASSISTANCE"));
        assertThat(preview(taxpayer,concept,"2026-09").finalAmount()).isEqualByComparingTo("100.00");
        consumer.consume(event(UUID.randomUUID(),"APPROVED",FIRST_UPDATE.plusHours(1),LocalDate.of(2026,9,1),null,"Servicios","UTILITY_SUBSIDY"));
        assertThat(preview(taxpayer,concept,"2026-09").finalAmount()).isEqualByComparingTo("100.00");
        consumer.consume(event(UUID.randomUUID(),"SUSPENDED",FIRST_UPDATE.plusHours(2),null,null,"Suspendido","TAX_EXEMPTION"));
        assertThat(preview(taxpayer,concept,"2026-09").finalAmount()).isEqualByComparingTo("100.00");
        consumer.consume(event(UUID.randomUUID(),"FINALIZED",FIRST_UPDATE.plusHours(3),null,LocalDate.of(2026,9,30),"Finalizado","TAX_EXEMPTION"));
        assertThat(preview(taxpayer,concept,"2026-09").finalAmount()).isEqualByComparingTo("100.00");
    }

    @Test void newlyApprovedBenefitDoesNotRetroactivelyChangeIssuedLiquidation(){
        TaxpayerReference taxpayer=taxpayer(true);TaxConcept concept=concept("M8-HISTORY");configuration(concept);
        Liquidation issued=liquidations.create(request(taxpayer,concept,"2026-09"));
        UUID originalId=issued.integrationId;
        assertThat(issued.finalAmount).isEqualByComparingTo("100.00");
        approvedRule(concept,"100",LocalDate.of(2026,9,1),null);
        consumer.consume(event(UUID.randomUUID(),"APPROVED",FIRST_UPDATE,LocalDate.of(2026,9,1),null,
            "Aprobado después de emitir","TAX_EXEMPTION"));
        entityManager.flush();entityManager.clear();
        Liquidation historical=liquidationRepository.findById(issued.id).orElseThrow();
        assertThat(historical.integrationId).isEqualTo(originalId);
        assertThat(historical.finalAmount).isEqualByComparingTo("100.00");
        assertThat(debts.findByLiquidationId(issued.id)).isPresent();
    }

    @Test void benefitValidityLimitsTheFiscalPeriodsEvenWhenRuleContinues(){
        TaxpayerReference taxpayer=taxpayer(true);TaxConcept concept=concept("M8-BENEFIT-DATES");configuration(concept);
        approvedRule(concept,"25",LocalDate.of(2026,1,1),null);
        consumer.consume(event(UUID.randomUUID(),"APPROVED",FIRST_UPDATE,LocalDate.of(2026,9,1),
            LocalDate.of(2026,10,31),"Vigencia limitada","TAX_EXEMPTION"));
        assertThat(preview(taxpayer,concept,"2026-08").finalAmount()).isEqualByComparingTo("100.00");
        assertThat(preview(taxpayer,concept,"2026-09").finalAmount()).isEqualByComparingTo("75.00");
        assertThat(preview(taxpayer,concept,"2026-10").finalAmount()).isEqualByComparingTo("75.00");
        assertThat(preview(taxpayer,concept,"2026-11").finalAmount()).isEqualByComparingTo("100.00");
    }

    @Test void ruleRequiresSupervisorIdentityAndRespectsValidity(){
        TaxpayerReference taxpayer=taxpayer(true);TaxConcept concept=concept("M8-VALIDITY");configuration(concept);
        SocialBenefitRule proposal=ruleService.propose(new SocialBenefitRuleService.RuleProposal(PROGRAM_ID,concept.code,
            new BigDecimal("45"),LocalDate.of(2026,10,1),LocalDate.of(2026,10,31)));
        ruleService.submit(proposal.id);
        SocialBenefitReference pending=consumer.consume(event(UUID.randomUUID(),"APPROVED",FIRST_UPDATE,
            LocalDate.of(2026,9,1),null,"Vigente","TAX_EXEMPTION"));
        assertThat(pending.resolutionStatus).isEqualTo("PENDING_TAX_RULE");
        assertThat(preview(taxpayer,concept,"2026-10").finalAmount()).isEqualByComparingTo("100.00");
        assertThatThrownBy(()->ruleService.approve(proposal.id)).isInstanceOf(BusinessException.class);
        as("supervisor.qa","SUPERVISOR",SUPERVISOR_ID);
        SocialBenefitRule approved=ruleService.approve(proposal.id);
        assertThat(approved.approvedActorId).isEqualTo(SUPERVISOR_ID);
        assertThat(approved.version).isEqualTo(1);
        assertThat(benefits.findById(pending.id).orElseThrow().resolutionStatus).isEqualTo("RESOLVED");
        assertThat(preview(taxpayer,concept,"2026-09").finalAmount()).isEqualByComparingTo("100.00");
        assertThat(preview(taxpayer,concept,"2026-10").finalAmount()).isEqualByComparingTo("55.00");
        assertThat(preview(taxpayer,concept,"2026-11").finalAmount()).isEqualByComparingTo("100.00");
        ruleService.disable(approved.id);
        assertThat(preview(taxpayer,concept,"2026-10").finalAmount()).isEqualByComparingTo("100.00");
        assertThat(rules.findById(approved.id).orElseThrow().enabled).isFalse();
    }

    private TaxpayerReference taxpayer(boolean canonical){
        TaxpayerReference person=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,
            "M1-LEGACY-"+UUID.randomUUID(),"12345678",null,"Ciudadano M8"));
        if(canonical)person.canonicalCitizenId=CITIZEN_ID;
        return person;
    }
    private TaxConcept concept(String code){return catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(code,code,null,TaxConceptType.FEE,"M5"));}
    private void configuration(TaxConcept concept){
        TaxConfiguration config=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,
            CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusYears(1),null));
        catalog.submit(config.id);TestActorSupport.asApprover(() -> catalog.approve(config.id));
    }
    private SocialBenefitRule approvedRule(TaxConcept concept,String percentage,LocalDate from,LocalDate until){
        return approvedRule(PROGRAM_ID,concept,percentage,from,until);
    }
    private SocialBenefitRule approvedRule(UUID program,TaxConcept concept,String percentage,LocalDate from,LocalDate until){
        SocialBenefitRule proposed=ruleService.propose(new SocialBenefitRuleService.RuleProposal(program,concept.code,
            new BigDecimal(percentage),from,until));
        ruleService.submit(proposed.id);as("supervisor.qa","SUPERVISOR",SUPERVISOR_ID);
        SocialBenefitRule approved=ruleService.approve(proposed.id);
        as("rentas.qa","RENTAS",UUID.fromString("0796688a-12fc-46b2-b8fc-9003e22d7fd2"));
        return approved;
    }
    private ApiDtos.LiquidationRequest request(TaxpayerReference taxpayer,TaxConcept concept,String period){
        return new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,period,BigDecimal.ZERO,LocalDate.now().plusDays(10));
    }
    private ApiDtos.LiquidationPreview preview(TaxpayerReference taxpayer,TaxConcept concept,String period){
        return liquidations.preview(request(taxpayer,concept,period));
    }
    private ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent event(UUID eventId,String status,OffsetDateTime updatedAt,
            LocalDate start,LocalDate end,String reason,String benefitType){
        var data=new ConfirmedInboundEvents.M8SocialBenefitData(BENEFIT_ID,CITIZEN_ID,APPLICATION_ID,PROGRAM_ID,
            PROGRAM_EDITION_ID,"Programa social",BENEFIT_TYPE_ID,benefitType,"Beneficio M8",null,
            status,reason,start,end,updatedAt);
        return new ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent(eventId,"socialBenefitUpdated","1.0",updatedAt,
            "desarrollo-social",UUID.randomUUID(),null,data);
    }
    private void as(String username,String role,UUID actorId){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity(username,null,actorId),null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
}
