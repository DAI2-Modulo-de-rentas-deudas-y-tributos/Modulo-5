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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test") @SpringBootTest @AutoConfigureMockMvc @Transactional
class FilteredApiTests {
    @Autowired MockMvc mvc; @Autowired CatalogService catalog; @Autowired LiquidationService liquidations;
    private TaxpayerReference taxpayer; private TaxConcept concept;

    @BeforeEach void setup(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("api-test",null),null,List.of(new SimpleGrantedAuthority("ROLE_RENTAS"))));taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"FILTER-1","111",null,"Filtro Uno"));concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("FILTER_FEE","Filtro",null,TaxConceptType.FEE,"M5"));TaxConfiguration configuration=catalog.createConfiguration(new ApiDtos.CreateTaxConfigurationRequest(concept.id,CalculationType.FIXED,null,new BigDecimal("100"),null,null,true,true,LocalDate.now().minusDays(1),null));catalog.submit(configuration.id);catalog.approve(configuration.id);liquidations.create(new ApiDtos.LiquidationRequest(taxpayer.id,concept.id,YearMonth.now().toString(),BigDecimal.ZERO,LocalDate.now().plusDays(10)));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void debtsSupportDocumentedFiltersPagingSortingAndResponseDto() throws Exception {mvc.perform(get("/api/v1/debts").param("taxpayerId",taxpayer.id.toString()).param("status","PENDING").param("page","0").param("size","1").param("sort","createdAt,desc")).andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1)).andExpect(jsonPath("$.content[0].taxpayerId").value(taxpayer.id)).andExpect(jsonPath("$.content[0].overdue").isBoolean()).andExpect(jsonPath("$.content[0].inPaymentPlan").isBoolean());}
    @Test void liquidationResponseContainsPersistedAuditComponents() throws Exception {mvc.perform(get("/api/v1/liquidations").param("conceptId",concept.id.toString()).param("period",YearMonth.now().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].components[0].type").value("BASE")).andExpect(jsonPath("$.content[0].components[0].sourceType").value("TaxConfiguration")).andExpect(jsonPath("$.content[0].finalAmount").value(100.00));}
    @Test void unknownFilterAndSortReturnStableBadRequest() throws Exception {mvc.perform(get("/api/v1/debts").param("madeUp","x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILTER"));mvc.perform(get("/api/v1/debts").param("sort","madeUp,asc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SORT"));}
}
