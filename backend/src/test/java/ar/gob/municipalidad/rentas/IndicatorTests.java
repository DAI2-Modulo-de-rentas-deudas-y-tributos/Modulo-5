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
class IndicatorTests {
    @Autowired CatalogService catalog;@Autowired LiquidationService liquidations;@Autowired PaymentService payments;@Autowired IndicatorService indicators;@Autowired TaxpayerQueryService taxpayerQueries;@Autowired DebtRepository debts;
    @BeforeEach void authenticate(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("tester",null),null,List.of(new SimpleGrantedAuthority("ROLE_RENTAS"),new SimpleGrantedAuthority("ROLE_CASHIER"))));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void indicatorsAndTaxpayerSummaryUseConfirmedEconomicData(){
        LocalDate today=LocalDate.now();
        ApiDtos.IndicatorSummaryResponse before=indicators.summary(null,null);
        BigDecimal todayBefore=indicators.collection(today,today).confirmedAmount();
        BigDecimal futureBefore=indicators.collection(today.plusDays(1),null).confirmedAmount();

        TaxpayerReference taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"IND-1","123",null,"Indicadores"));
        TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("IND-CONCEPT","Indicador",null,TaxConceptType.FEE,"M5"));
        TaxConfiguration c=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,today.minusDays(1),null));
        catalog.submit(c.id);TestActorSupport.asApprover(() -> catalog.approve(c.id));
        liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,today.minusDays(1)));
        Debt debt=debts.findByTaxpayerId(taxpayer.id).get(0);
        payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,PaymentMethod.CASH,new BigDecimal("40"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40")))));

        ApiDtos.IndicatorSummaryResponse result=indicators.summary(null,null);
        assertThat(result.collection().paymentCount()).isEqualTo(before.collection().paymentCount()+1);
        assertThat(result.collection().confirmedAmount()).isEqualByComparingTo(before.collection().confirmedAmount().add(new BigDecimal("40.00")));
        assertThat(result.collection().allocatedAmount()).isEqualByComparingTo(before.collection().allocatedAmount().add(new BigDecimal("40.00")));
        assertThat(result.collection().unallocatedAmount()).isEqualByComparingTo(before.collection().unallocatedAmount());
        assertThat(indicators.collection(today,today).confirmedAmount()).isEqualByComparingTo(todayBefore.add(new BigDecimal("40.00")));
        assertThat(indicators.collection(today.plusDays(1),null).confirmedAmount()).isEqualByComparingTo(futureBefore);
        assertThat(result.debt().debtCount()).isEqualTo(before.debt().debtCount()+1);
        assertThat(result.debt().openCount()).isEqualTo(before.debt().openCount()+1);
        assertThat(result.debt().originalAmount()).isEqualByComparingTo(before.debt().originalAmount().add(new BigDecimal("100.00")));
        assertThat(result.debt().outstandingAmount()).isEqualByComparingTo(before.debt().outstandingAmount().add(new BigDecimal("60.00")));
        assertThat(result.delinquency().overdueDebtCount()).isEqualTo(before.delinquency().overdueDebtCount()+1);
        assertThat(result.delinquency().overdueAmount()).isEqualByComparingTo(before.delinquency().overdueAmount().add(new BigDecimal("60.00")));
        assertThat(taxpayerQueries.summary(taxpayer.id).outstandingDebt()).isEqualByComparingTo("60.00");
    }
}
