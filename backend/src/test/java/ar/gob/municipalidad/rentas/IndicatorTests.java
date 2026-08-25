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
    @Test void indicatorsAndTaxpayerSummaryUseConfirmedEconomicData(){TaxpayerReference taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"IND-1","123",null,"Indicadores"));TaxConcept concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("IND-CONCEPT","Indicador",null,TaxConceptType.FEE,"M5"));TaxConfiguration c=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(c.id);catalog.approve(c.id);liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().minusDays(1)));Debt debt=debts.findByTaxpayerId(taxpayer.id).get(0);payments.register(new ApiDtos.RegisterPaymentRequest(taxpayer.id,PaymentMethod.CASH,new BigDecimal("40"),List.of(new ApiDtos.AllocationRequest(debt.id,new BigDecimal("40")))));ApiDtos.IndicatorSummaryResponse result=indicators.summary(null,null);assertThat(result.collection().confirmedAmount()).isEqualByComparingTo("40.00");assertThat(result.debt().outstandingAmount()).isEqualByComparingTo("60.00");assertThat(result.delinquency().overdueDebtCount()).isEqualTo(1);assertThat(taxpayerQueries.summary(taxpayer.id).outstandingDebt()).isEqualByComparingTo("60.00");}
}
