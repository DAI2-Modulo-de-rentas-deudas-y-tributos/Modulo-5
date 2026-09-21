package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExemptionHistoryTests {
    @Autowired ExemptionService exemptions;
    @Autowired ExemptionRequestDocumentRepository documents;
    @Autowired CatalogService catalog;
    @Autowired MockMvc mvc;
    @Autowired DemoUserRepository demoUsers;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService demoAuth;

    private TaxpayerReference taxpayer;
    private TaxConcept concept;
    private DemoAuthSessions demo;

    @BeforeEach void setUp() {
        taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"EXEMPTION-HISTORY","30333444",null,"Historial exención"));
        concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("EXEMPTION-HISTORY","Concepto historial",null,TaxConceptType.FEE,"M5"));
        demo=new DemoAuthSessions(demoUsers,taxpayers,encoder,demoAuth);
        authenticate(new AuthenticatedIdentity("taxpayer-owner",taxpayer.id),"TAXPAYER");
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void pendingHistoryShowsCreation() {
        ExemptionRequest request=create();

        ApiDtos.ExemptionRequestHistoryResponse history=exemptions.history(request.id);

        assertThat(history.currentStatus()).isEqualTo(ExemptionRequestStatus.PENDING);
        assertThat(history.order()).isEqualTo("ASC");
        assertThat(history.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo("REQUESTED");
            assertThat(entry.status()).isEqualTo(ExemptionRequestStatus.PENDING);
            assertThat(entry.date()).isEqualTo(request.requestedAt);
            assertThat(entry.message()).isNull();
        });
        assertThat(history.result()).isNull();
    }

    @Test void underReviewHistoryShowsTransition() {
        ExemptionRequest request=create();
        exemptions.start(request.id);

        assertThat(exemptions.history(request.id).entries()).extracting(ApiDtos.ExemptionRequestHistoryEntry::type)
            .containsExactly("REQUESTED","REVIEW_STARTED");
    }

    @Test void documentationRequestMessageIsPublicForOwner() throws Exception {
        ExemptionRequest request=create();
        exemptions.start(request.id);
        exemptions.requestDocumentation(request.id,"Falta constancia municipal");
        String owner=demo.token(DemoRole.TAXPAYER,taxpayer.id);
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/exemption-requests/{id}/history",request.id).header("X-Demo-Session",owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[2].type").value("DOCUMENTATION_REQUIRED"))
            .andExpect(jsonPath("$.entries[2].message").value("Falta constancia municipal"));
        mvc.perform(get("/api/v1/exemption-requests/{id}",request.id).header("X-Demo-Session",owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resolutionReason").value("Falta constancia municipal"));
    }

    @Test void submittedDocumentAppearsWithoutExposingAuditPayload() {
        ExemptionRequest request=create();
        exemptions.start(request.id);
        exemptions.requestDocumentation(request.id,"Falta documento");
        exemptions.submitDocumentation(request.id,document("DOC-LATER","CERTIFICATE","constancia.pdf"));

        ApiDtos.ExemptionRequestHistoryEntry uploaded=exemptions.history(request.id).entries().stream()
            .filter(entry -> entry.type().equals("DOCUMENT_UPLOADED")).findFirst().orElseThrow();
        assertThat(uploaded.document().externalDocumentId()).isEqualTo("DOC-LATER");
        assertThat(uploaded.document().fileName()).isEqualTo("constancia.pdf");
        assertThat(uploaded.status()).isEqualTo(ExemptionRequestStatus.UNDER_REVIEW);
        assertThat(uploaded.message()).isNull();
        assertThat(request.status).isEqualTo(ExemptionRequestStatus.UNDER_REVIEW);
    }

    @Test void pendingResolutionObservationIsHiddenFromTaxpayerAndVisibleToStaff() throws Exception {
        ExemptionRequest request=create();
        exemptions.start(request.id);
        exemptions.submit(request.id,"OBSERVACIÓN INTERNA RESERVADA");
        String owner=demo.token(DemoRole.TAXPAYER,taxpayer.id);
        String rentas=demo.token(DemoRole.RENTAS);
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/exemption-requests/{id}/history",request.id).header("X-Demo-Session",owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.entries[2].type").value("SUBMITTED_FOR_RESOLUTION"))
            .andExpect(jsonPath("$.entries[2].message").doesNotExist());
        mvc.perform(get("/api/v1/exemption-requests/{id}",request.id).header("X-Demo-Session",owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resolutionReason").doesNotExist());
        mvc.perform(get("/api/v1/exemption-requests/{id}/history",request.id).header("X-Demo-Session",rentas))
            .andExpect(status().isOk()).andExpect(jsonPath("$.entries[2].message").value("OBSERVACIÓN INTERNA RESERVADA"));
        mvc.perform(get("/api/v1/exemption-requests/{id}",request.id).header("X-Demo-Session",rentas))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resolutionReason").value("OBSERVACIÓN INTERNA RESERVADA"));
    }

    @Test void approvedHistoryUsesFinalPercentageAndValidity() throws Exception {
        ExemptionRequest request=create();
        exemptions.start(request.id);
        exemptions.submit(request.id,"Análisis interno");
        LocalDate approvedFrom=LocalDate.of(2027,1,1);
        LocalDate approvedUntil=LocalDate.of(2027,6,30);
        exemptions.approve(request.id,new ApiDtos.ApproveExemptionRequest(new BigDecimal("75"),approvedFrom,approvedUntil,"Aprobación interna"));
        String owner=demo.token(DemoRole.TAXPAYER,taxpayer.id);
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/exemption-requests/{id}/history",request.id).header("X-Demo-Session",owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentStatus").value("APPROVED"))
            .andExpect(jsonPath("$.result.percentage").value(75))
            .andExpect(jsonPath("$.result.validFrom").value("2027-01-01"))
            .andExpect(jsonPath("$.result.validUntil").value("2027-06-30"))
            .andExpect(jsonPath("$.result.status").value("ACTIVE"))
            .andExpect(jsonPath("$.result.approvedAt").exists())
            .andExpect(jsonPath("$.result.resolvedAt").exists());
        mvc.perform(get("/api/v1/exemption-requests/{id}",request.id).header("X-Demo-Session",owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resolutionReason").doesNotExist());
    }

    @Test void rejectedHistoryAndDetailShowPublicReason() throws Exception {
        ExemptionRequest request=create();
        exemptions.start(request.id);
        exemptions.submit(request.id,"Análisis interno");
        exemptions.reject(request.id,"No acredita la condición solicitada");
        String owner=demo.token(DemoRole.TAXPAYER,taxpayer.id);
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/exemption-requests/{id}/history",request.id).header("X-Demo-Session",owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[3].type").value("REJECTED"))
            .andExpect(jsonPath("$.entries[3].message").value("No acredita la condición solicitada"))
            .andExpect(jsonPath("$.result.status").value("REJECTED"))
            .andExpect(jsonPath("$.result.message").value("No acredita la condición solicitada"));
        mvc.perform(get("/api/v1/exemption-requests/{id}",request.id).header("X-Demo-Session",owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resolutionReason").value("No acredita la condición solicitada"));
    }

    @Test void foreignTaxpayerCannotReadHistory() throws Exception {
        ExemptionRequest request=create();
        TaxpayerReference foreign=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"EXEMPTION-HISTORY-FOREIGN","30999766",null,"Contribuyente ajeno"));
        String session=demo.token(DemoRole.TAXPAYER,foreign.id);
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/exemption-requests/{id}/history",request.id).header("X-Demo-Session",session))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
    }

    @ParameterizedTest
    @EnumSource(value=DemoRole.class,names={"RENTAS","SUPERVISOR","AUDITOR"})
    void authorizedStaffCanReadFunctionalHistory(DemoRole role) throws Exception {
        ExemptionRequest request=create();
        String session=demo.token(role);
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/exemption-requests/{id}/history",request.id).header("X-Demo-Session",session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value(request.id));
    }

    @Test void nonexistentRequestReturnsNotFound() throws Exception {
        String session=demo.token(DemoRole.RENTAS);
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/exemption-requests/{id}/history",Long.MAX_VALUE).header("X-Demo-Session",session))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void historyOrderIsChronologicalAndDeterministic() {
        ExemptionRequest request=exemptions.create(createRequest(List.of(document("DOC-1","IDENTITY","uno.pdf"),document("DOC-2","CERTIFICATE","dos.pdf"))));
        List<ExemptionRequestDocument> stored=documents.findByExemptionRequestIdOrderByUploadedAtAscIdAsc(request.id);
        OffsetDateTime sameInstant=request.requestedAt.plusSeconds(1);
        stored.forEach(document -> document.uploadedAt=sameInstant);
        documents.saveAllAndFlush(stored);

        ApiDtos.ExemptionRequestHistoryResponse history=exemptions.history(request.id);

        assertThat(history.entries()).extracting(ApiDtos.ExemptionRequestHistoryEntry::date).isSorted();
        assertThat(history.entries()).extracting(ApiDtos.ExemptionRequestHistoryEntry::type)
            .containsExactly("REQUESTED","DOCUMENT_UPLOADED","DOCUMENT_UPLOADED");
        assertThat(history.entries().stream().filter(entry -> entry.document()!=null).map(entry -> entry.document().externalDocumentId()))
            .containsExactly("DOC-1","DOC-2");
    }

    private ExemptionRequest create() { return exemptions.create(createRequest(null)); }

    private ApiDtos.CreateExemptionRequest createRequest(List<ApiDtos.SubmitDocumentationRequest> initialDocuments) {
        return new ApiDtos.CreateExemptionRequest(taxpayer.id,concept.id,"Motivo de la solicitud",new BigDecimal("50"),LocalDate.of(2026,10,1),LocalDate.of(2026,12,31),initialDocuments);
    }

    private ApiDtos.SubmitDocumentationRequest document(String externalId,String type,String fileName) {
        return new ApiDtos.SubmitDocumentationRequest(externalId,type,fileName);
    }

    private void authenticate(AuthenticatedIdentity identity,String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
}
