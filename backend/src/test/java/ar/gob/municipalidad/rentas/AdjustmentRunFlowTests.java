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

@ActiveProfiles("test") @SpringBootTest @Transactional
class AdjustmentRunFlowTests {
    @Autowired CatalogService catalog; @Autowired LiquidationService liquidations; @Autowired AdjustmentService adjustments;
    @Autowired LiquidationRunService runs; @Autowired DebtRepository debts; @Autowired LiquidationRepository liquidationRepository;

    @BeforeEach void authenticate(){var authorities=List.of("RENTAS","SUPERVISOR").stream().map(x->new SimpleGrantedAuthority("ROLE_"+x)).toList();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("tester",null),null,authorities));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void adjustmentChangesDebtOnlyWhenApproved(){Debt debt=debt("ADJUST-1","ADJUST-CONCEPT-1");AdjustmentRequest request=adjustments.create(new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.DISCOUNT,new BigDecimal("25"),"Bonificación autorizada"));assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("100.00");adjustments.approve(request.id,"Verificado");assertThat(debts.findById(debt.id).orElseThrow().outstandingBalance).isEqualByComparingTo("75.00");assertThat(debts.findById(debt.id).orElseThrow().originalAmount).isEqualByComparingTo("100.00");}

    @Test void adjustmentCannotReduceDebtBelowAmountAlreadyPaid(){Debt debt=debt("ADJUST-2","ADJUST-CONCEPT-2");liquidationsForPayment(debt,new BigDecimal("40"));AdjustmentRequest request=adjustments.create(new ApiDtos.CreateAdjustmentRequest(debt.id,AdjustmentType.CORRECTION,new BigDecimal("30"),"Corrección errónea"));assertThatThrownBy(()->adjustments.approve(request.id,null)).isInstanceOf(BusinessException.class).hasMessageContaining("debajo de lo pagado");assertThat(request.status).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);}

    @Test void runPreviewCountsErrorsWithoutCreatingLiquidations(){TaxpayerReference taxpayer=taxpayer("RUN-1");TaxConcept concept=concept("RUN-CONCEPT-1");activate(concept.id);long before=liquidationRepository.count();LiquidationRun run=runs.create(new ApiDtos.CreateLiquidationRunRequest(concept.id,YearMonth.now().toString(),LocalDate.now().plusDays(30),List.of(new ApiDtos.LiquidationRunItemRequest(taxpayer.id,BigDecimal.ZERO),new ApiDtos.LiquidationRunItemRequest(999999L,BigDecimal.ZERO))));ApiDtos.LiquidationRunDetail detail=runs.preview(run.id);assertThat(detail.run().validItems).isEqualTo(1);assertThat(detail.run().errorItems).isEqualTo(1);assertThat(liquidationRepository.count()).isEqualTo(before);}

    @Test void approvedRunExecutesOnlyValidItems(){TaxpayerReference taxpayer=taxpayer("RUN-2");TaxConcept concept=concept("RUN-CONCEPT-2");activate(concept.id);LiquidationRun run=runs.create(new ApiDtos.CreateLiquidationRunRequest(concept.id,YearMonth.now().toString(),LocalDate.now().plusDays(30),List.of(new ApiDtos.LiquidationRunItemRequest(taxpayer.id,BigDecimal.ZERO),new ApiDtos.LiquidationRunItemRequest(999998L,BigDecimal.ZERO))));runs.preview(run.id);runs.submit(run.id);runs.approve(run.id,null);runs.execute(run.id);assertThat(run.status).isEqualTo(LiquidationRunStatus.EXECUTED);assertThat(debts.findByTaxpayerId(taxpayer.id)).hasSize(1);}

    private void liquidationsForPayment(Debt debt,BigDecimal paid){debt.outstandingBalance=debt.outstandingBalance.subtract(paid);debt.status=DebtStatus.PARTIALLY_PAID;}
    private Debt debt(String external,String code){TaxpayerReference taxpayer=taxpayer(external);TaxConcept concept=concept(code);activate(concept.id);liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(30)));return debts.findByTaxpayerId(taxpayer.id).get(0);}
    private TaxpayerReference taxpayer(String external){return catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,external,"123",null,"Persona "+external));}
    private TaxConcept concept(String code){return catalog.createConcept(new ApiDtos.CreateTaxConceptRequest(code,code,null,TaxConceptType.FEE,"M5"));}
    private void activate(Long conceptId){TaxConfiguration c=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(conceptId,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(c.id);catalog.approve(c.id);}
}
