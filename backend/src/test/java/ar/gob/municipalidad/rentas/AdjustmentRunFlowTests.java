package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@ActiveProfiles("test") @SpringBootTest @AutoConfigureMockMvc @Transactional
class AdjustmentRunFlowTests {
    @Autowired CatalogService catalog; @Autowired LiquidationService liquidations; @Autowired AdjustmentService adjustments;
    @Autowired LiquidationRunService runs; @Autowired DebtRepository debts; @Autowired LiquidationRepository liquidationRepository;
    @Autowired OutboxRepository outbox; @Autowired com.fasterxml.jackson.databind.ObjectMapper json; @Autowired MockMvc mvc;

    @BeforeEach void authenticate(){var authorities=List.of("RENTAS","SUPERVISOR").stream().map(x->new SimpleGrantedAuthority("ROLE_"+x)).toList();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("tester",null),null,authorities));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void adjustmentChangesDebtAndPublishesTypedPlatformEventOnlyWhenApproved() throws Exception {Debt debt=debt("ADJUST-1","ADJUST-CONCEPT-1");AdjustmentRequest request=adjustments.create(new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.DISCOUNT,new BigDecimal("25"),"Bonificación autorizada"));assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");TestActorSupport.asApprover(() -> adjustments.approve(request.id,"Verificado"));assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("75.00");assertThat(debts.findById(debt.id).orElseThrow().originalAmount).isEqualByComparingTo("100.00");List<OutboxEvent> events=outbox.findAll().stream().filter(x->x.eventType.equals("debtAdjusted")&&x.aggregateId.equals(debt.id.toString())).toList();assertThat(events).hasSize(1);assertThat(events.get(0).targetModule).isEqualTo("PLATFORM");var payload=json.readTree(events.get(0).payload);assertThat(payload.path("debtId").longValue()).isEqualTo(debt.id);assertThat(payload.path("adjustmentId").longValue()).isEqualTo(request.id);}

    @Test void rejectedAdjustmentDoesNotPublishDebtAdjusted(){Debt debt=debt("ADJUST-REJECTED","ADJUST-CONCEPT-REJECTED");AdjustmentRequest request=adjustments.create(new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.DISCOUNT,new BigDecimal("10"),"Solicitud de prueba"));TestActorSupport.asApprover(() -> adjustments.reject(request.id,"No corresponde"));assertThat(outbox.findAll()).noneMatch(x->x.eventType.equals("debtAdjusted")&&x.aggregateId.equals(debt.id.toString()));}

    @Test void adjustmentCannotReduceDebtBelowAmountAlreadyPaid(){Debt debt=debt("ADJUST-2","ADJUST-CONCEPT-2");liquidationsForPayment(debt,new BigDecimal("40"));AdjustmentRequest request=adjustments.create(new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.CORRECTION,new BigDecimal("30"),"Corrección errónea"));assertThatThrownBy(()->TestActorSupport.asApprover(() -> adjustments.approve(request.id,null))).isInstanceOf(BusinessException.class).hasMessageContaining("debajo de lo pagado");assertThat(request.status).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);}

    @Test void runPreviewCountsErrorsWithoutCreatingLiquidations(){TaxpayerReference taxpayer=taxpayer("RUN-1");TaxConcept concept=concept("RUN-CONCEPT-1");activate(concept.id);long before=liquidationRepository.count();LiquidationRun run=runs.create(new ApiDtos.CreateLiquidationRunRequest(concept.id,YearMonth.now().toString(),LocalDate.now().plusDays(30),List.of(new ApiDtos.LiquidationRunItemRequest(taxpayer.id,BigDecimal.ZERO),new ApiDtos.LiquidationRunItemRequest(999999L,BigDecimal.ZERO))));ApiDtos.LiquidationRunDetail detail=runs.preview(run.id);assertThat(detail.run().validItems).isEqualTo(1);assertThat(detail.run().errorItems).isEqualTo(1);assertThat(liquidationRepository.count()).isEqualTo(before);}

    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void approvedRunExecutesOnlyValidItems(){TaxpayerReference taxpayer=taxpayer("RUN-2");TaxConcept concept=concept("RUN-CONCEPT-2");activate(concept.id);LiquidationRun run=runs.create(new ApiDtos.CreateLiquidationRunRequest(concept.id,YearMonth.now().toString(),LocalDate.now().plusDays(30),List.of(new ApiDtos.LiquidationRunItemRequest(taxpayer.id,BigDecimal.ZERO),new ApiDtos.LiquidationRunItemRequest(999998L,BigDecimal.ZERO))));runs.preview(run.id);runs.submit(run.id);TestActorSupport.asApprover(() -> runs.approve(run.id,null));LiquidationRun executedRun=runs.execute(run.id);assertThat(executedRun.status).isEqualTo(LiquidationRunStatus.EXECUTED);assertThat(debts.findByTaxpayerId(taxpayer.id)).hasSize(1);}

    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void duplicateIsReportedInPreviewAndExecutionRaceFinishesWithItemError() throws Exception {
        String suffix=java.util.UUID.randomUUID().toString();
        TaxpayerReference previewTaxpayer=taxpayer("RUN-DUP-PREVIEW-"+suffix);
        TaxConcept previewConcept=concept("RUN-DUP-PREVIEW-"+suffix);
        activate(previewConcept.id);
        String previewPeriod=YearMonth.now().toString();
        liquidations.create(new ApiDtos.LiquidationRequest(previewTaxpayer.id,previewConcept.id,previewPeriod,BigDecimal.ZERO,LocalDate.now().plusDays(30)));
        long liquidationCount=liquidationRepository.count();
        LiquidationRun duplicateRun=runs.create(new ApiDtos.CreateLiquidationRunRequest(previewConcept.id,previewPeriod,LocalDate.now().plusDays(30),List.of(new ApiDtos.LiquidationRunItemRequest(previewTaxpayer.id,BigDecimal.ZERO))));
        mvc.perform(post("/api/v1/liquidation-runs/{id}/preview",duplicateRun.id).with(authentication(asRole("RENTAS"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.run.validItems").value(0))
            .andExpect(jsonPath("$.run.errorItems").value(1))
            .andExpect(jsonPath("$.items[0].status").value("ERROR"))
            .andExpect(jsonPath("$.items[0].errorCode").value("DUPLICATE_LIQUIDATION"));
        assertThat(liquidationRepository.count()).isEqualTo(liquidationCount);

        SecurityContextHolder.getContext().setAuthentication(asRole("RENTAS"));
        TaxpayerReference executionTaxpayer=taxpayer("RUN-DUP-EXECUTE-"+suffix);
        TaxConcept executionConcept=concept("RUN-DUP-EXECUTE-"+suffix);
        activate(executionConcept.id);
        String executionPeriod=YearMonth.now().plusMonths(1).toString();
        LiquidationRun executionRun=runs.create(new ApiDtos.CreateLiquidationRunRequest(executionConcept.id,executionPeriod,LocalDate.now().plusDays(30),List.of(new ApiDtos.LiquidationRunItemRequest(executionTaxpayer.id,BigDecimal.ZERO))));
        runs.preview(executionRun.id);
        runs.submit(executionRun.id);
        TestActorSupport.asApprover(() -> runs.approve(executionRun.id,null));
        liquidations.create(new ApiDtos.LiquidationRequest(executionTaxpayer.id,executionConcept.id,executionPeriod,BigDecimal.ZERO,LocalDate.now().plusDays(30)));

        mvc.perform(post("/api/v1/liquidation-runs/{id}/execute",executionRun.id).with(authentication(asRole("RENTAS"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.validItems").value(0))
            .andExpect(jsonPath("$.errorItems").value(1));
        SecurityContextHolder.getContext().setAuthentication(asRole("RENTAS"));
        mvc.perform(get("/api/v1/liquidation-runs/{id}",executionRun.id).with(authentication(asRole("RENTAS"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.run.status").value("FAILED"))
            .andExpect(jsonPath("$.items[0].status").value("ERROR"))
            .andExpect(jsonPath("$.items[0].errorCode").value("DUPLICATE_LIQUIDATION"));
        assertThat(liquidationRepository.existsByTaxpayerIdAndTaxConceptIdAndPeriod(executionTaxpayer.id,executionConcept.id,executionPeriod)).isTrue();
        assertThat(debts.findByTaxpayerId(executionTaxpayer.id)).hasSize(1);
    }

    private void liquidationsForPayment(Debt debt,BigDecimal paid){debt.outstandingBalance=debt.outstandingBalance.subtract(paid);debt.status=DebtStatus.PARTIALLY_PAID;}
    private Debt debt(String external,String code){TaxpayerReference taxpayer=taxpayer(external);TaxConcept concept=concept(code);activate(concept.id);liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));return debts.findByTaxpayerId(taxpayer.id).get(0);}
    private TaxpayerReference taxpayer(String external){return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,external,"123",null,"Persona "+external));}
    private TaxConcept concept(String code){return catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(code,code,null,TaxConceptType.FEE,"M5"));}
    private void activate(Long conceptId){TaxConfiguration c=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(conceptId,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(c.id);TestActorSupport.asApprover(() -> catalog.approve(c.id));}
    private UsernamePasswordAuthenticationToken asRole(String role){return new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("tester",null),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
}
