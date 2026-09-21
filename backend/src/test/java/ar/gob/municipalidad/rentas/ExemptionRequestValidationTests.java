package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExemptionRequestValidationTests {
    @Autowired ExemptionService exemptions;
    @Autowired ExemptionRequestRepository requests;
    @Autowired ExemptionRepository approvedExemptions;
    @Autowired CatalogService catalog;
    @Autowired TaxConceptRepository concepts;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DemoUserRepository demoUsers;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;

    private TaxpayerReference taxpayer;
    private TaxConcept concept;
    private DemoAuthSessions demo;

    @BeforeEach void setUp() {
        taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"EXEMPTION-VALIDATION","30111222",null,"Validación exención"));
        concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("EXEMPTION-VALIDATION","Concepto validable",null,TaxConceptType.FEE,"M5"));
        demo=new DemoAuthSessions(demoUsers,taxpayers,encoder,demoAuth);
        authenticate(new AuthenticatedIdentity("taxpayer-owner",taxpayer.id),"TAXPAYER");
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void nonexistentConceptIsRejectedWithoutPersistence() {
        assertBusinessError(() -> exemptions.create(request(Long.MAX_VALUE,"Motivo válido",new BigDecimal("50"),date(1),date(31))),"NOT_FOUND",404);
        assertThat(requests.count()).isZero();
    }

    @Test void inactiveConceptIsRejectedWithoutPersistence() {
        concept.active=false;
        concepts.saveAndFlush(concept);

        assertBusinessError(() -> exemptions.create(validRequest()),"TAX_CONCEPT_NOT_ACTIVE",422);
        assertThat(requests.count()).isZero();
    }

    @Test void missingValidFromIsRejectedWithoutPersistence() {
        assertBusinessError(() -> exemptions.create(request(concept.id,"Motivo válido",new BigDecimal("50"),null,date(31))),"INVALID_VALIDITY_RANGE",422);
        assertThat(requests.count()).isZero();
    }

    @Test void missingValidUntilIsRejectedWithoutPersistence() {
        assertBusinessError(() -> exemptions.create(request(concept.id,"Motivo válido",new BigDecimal("50"),date(1),null)),"INVALID_VALIDITY_RANGE",422);
        assertThat(requests.count()).isZero();
    }

    @Test void invalidValidityRangeIsRejectedWithoutPersistence() {
        assertBusinessError(() -> exemptions.create(request(concept.id,"Motivo válido",new BigDecimal("50"),date(31),date(1))),"INVALID_VALIDITY_RANGE",422);
        assertThat(requests.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"0","100.01"})
    void invalidPercentageIsRejectedByServiceWithoutPersistence(String percentage) {
        assertBusinessError(() -> exemptions.create(request(concept.id,"Motivo válido",new BigDecimal(percentage),date(1),date(31))),"INVALID_EXEMPTION_PERCENTAGE",422);
        assertThat(requests.count()).isZero();
    }

    @Test void blankReasonIsRejectedByServiceWithoutPersistence() {
        assertBusinessError(() -> exemptions.create(request(concept.id,"   ",new BigDecimal("50"),date(1),date(31))),"INVALID_REQUEST",422);
        assertThat(requests.count()).isZero();
    }

    @Test void apiBeanValidationRejectsInvalidFieldsBeforePersistence() throws Exception {
        var invalid=request(concept.id," ",BigDecimal.ZERO,null,date(31));

        mvc.perform(post("/api/v1/exemption-requests")
                .header("X-Demo-Session",demo.token(DemoRole.TAXPAYER,taxpayer.id))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(requests.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value=ExemptionRequestStatus.class,names={"PENDING","UNDER_REVIEW","DOCUMENTATION_REQUIRED","PENDING_RESOLUTION"})
    void overlappingNonTerminalRequestIsRejected(ExemptionRequestStatus status) {
        ExemptionRequest existing=exemptions.create(request(concept.id,"Solicitud inicial",new BigDecimal("25"),date(1),LocalDate.of(2030,12,31)));
        existing.status=status;
        requests.saveAndFlush(existing);

        assertBusinessError(() -> exemptions.create(request(concept.id,"Solicitud superpuesta",new BigDecimal("50"),LocalDate.of(2030,1,1),LocalDate.of(2030,12,31))),"OVERLAPPING_EXEMPTION_REQUEST",422);
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test void rejectedRequestDoesNotBlockAValidRequest() {
        ExemptionRequest rejected=exemptions.create(request(concept.id,"Solicitud rechazada",new BigDecimal("25"),date(1),date(31)));
        rejected.status=ExemptionRequestStatus.REJECTED;
        requests.saveAndFlush(rejected);

        ExemptionRequest created=exemptions.create(request(concept.id,"Nueva solicitud",new BigDecimal("50"),date(1),date(31)));

        assertThat(created.status).isEqualTo(ExemptionRequestStatus.PENDING);
        assertThat(requests.count()).isEqualTo(2);
    }

    @Test void activeApprovedExemptionDoesNotInventAnAdditionalBlock() {
        ExemptionRequest approved=exemptions.create(request(concept.id,"Solicitud aprobada",new BigDecimal("25"),date(1),date(31)));
        exemptions.start(approved.id);
        exemptions.submit(approved.id);
        exemptions.approve(approved.id);

        ExemptionRequest created=exemptions.create(request(concept.id,"Nueva solicitud",new BigDecimal("50"),date(1),date(31)));

        assertThat(approvedExemptions.findByTaxpayerIdAndTaxConceptIdAndStatus(taxpayer.id,concept.id,"ACTIVE")).hasSize(1);
        assertThat(created.status).isEqualTo(ExemptionRequestStatus.PENDING);
    }

    @Test void validRequestStartsPending() {
        ExemptionRequest created=exemptions.create(validRequest());

        assertThat(created.status).isEqualTo(ExemptionRequestStatus.PENDING);
        assertThat(created.reason).isEqualTo("Motivo válido");
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test void taxpayerOwnershipIsStillEnforcedByApi() throws Exception {
        TaxpayerReference foreign=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"EXEMPTION-FOREIGN","30999888",null,"Contribuyente ajeno"));
        String body=json.writeValueAsString(validRequest());
        String foreignSession=demo.token(DemoRole.TAXPAYER,foreign.id);
        String ownerSession=demo.token(DemoRole.TAXPAYER,taxpayer.id);
        SecurityContextHolder.clearContext();

        mvc.perform(post("/api/v1/exemption-requests")
                .header("X-Demo-Session",foreignSession)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
        assertThat(requests.count()).isZero();

        mvc.perform(post("/api/v1/exemption-requests")
                .header("X-Demo-Session",ownerSession)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(requests.count()).isEqualTo(1);
    }

    private ApiDtos.CreateExemptionRequest validRequest() {
        return request(concept.id,"Motivo válido",new BigDecimal("50"),date(1),date(31));
    }

    private ApiDtos.CreateExemptionRequest request(Long conceptId,String reason,BigDecimal percentage,LocalDate from,LocalDate until) {
        return new ApiDtos.CreateExemptionRequest(taxpayer.id,conceptId,reason,percentage,from,until);
    }

    private LocalDate date(int day) { return LocalDate.of(2026,10,day); }

    private void authenticate(AuthenticatedIdentity identity,String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }

    private void assertBusinessError(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,String code,int status) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(BusinessException.class,exception -> {
            assertThat(exception.code).isEqualTo(code);
            assertThat(exception.status).isEqualTo(status);
        });
    }
}
