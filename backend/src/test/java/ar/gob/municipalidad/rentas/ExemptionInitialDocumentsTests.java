package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExemptionInitialDocumentsTests {
    @Autowired ExemptionService exemptions;
    @Autowired ExemptionRequestRepository requests;
    @Autowired ExemptionRequestDocumentRepository documents;
    @Autowired CatalogService catalog;
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
        taxpayer=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"EXEMPTION-DOCUMENTS","30222333",null,"Documentación exención"));
        concept=catalog.createConcept(new ApiDtos.CreateTaxConceptRequest("EXEMPTION-DOCUMENTS","Concepto documental",null,TaxConceptType.FEE,"M5"));
        demo=new DemoAuthSessions(demoUsers,taxpayers,encoder,demoAuth);
        authenticate(new AuthenticatedIdentity("taxpayer-owner",taxpayer.id),"TAXPAYER");
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void creationWithoutDocumentsRemainsBackwardCompatible() {
        ExemptionRequest created=exemptions.create(request(null));

        assertThat(created.status).isEqualTo(ExemptionRequestStatus.PENDING);
        assertThat(documents.findByExemptionRequestId(created.id)).isEmpty();
    }

    @Test void creationWithOneDocumentLinksMetadataAndKeepsPending() {
        ExemptionRequest created=exemptions.create(request(List.of(document("DOC-1","IDENTITY","dni.pdf"))));

        List<ExemptionRequestDocument> stored=documents.findByExemptionRequestId(created.id);
        assertThat(created.status).isEqualTo(ExemptionRequestStatus.PENDING);
        assertThat(stored).singleElement().satisfies(document -> {
            assertThat(document.exemptionRequestId).isEqualTo(created.id);
            assertThat(document.externalDocumentId).isEqualTo("DOC-1");
            assertThat(document.documentType).isEqualTo("IDENTITY");
            assertThat(document.fileName).isEqualTo("dni.pdf");
            assertThat(document.uploadedBy).isEqualTo("taxpayer-owner");
            assertThat(document.uploadedAt).isNotNull();
        });
    }

    @Test void creationWithSeveralDocumentsPersistsAllForTheSameRequest() {
        ExemptionRequest created=exemptions.create(request(List.of(
            document("DOC-1","IDENTITY","dni.pdf"),
            document("DOC-2","CERTIFICATE","certificado.pdf"),
            document("DOC-3","NOTE",null))));

        assertThat(documents.findByExemptionRequestIdOrderByUploadedAtAscIdAsc(created.id))
            .extracting(document -> document.externalDocumentId)
            .containsExactly("DOC-1","DOC-2","DOC-3");
        assertThat(created.status).isEqualTo(ExemptionRequestStatus.PENDING);
    }

    @Test void invalidInitialDocumentRollsBackRequestAndPartialDocuments() {
        assertThatThrownBy(() -> exemptions.create(request(List.of(
            document("DOC-VALID","IDENTITY","dni.pdf"),
            document(" ","CERTIFICATE","invalido.pdf")))))
            .isInstanceOfSatisfying(BusinessException.class,exception -> assertThat(exception.code).isEqualTo("INVALID_DOCUMENT"));

        assertThat(requests.count()).isZero();
        assertThat(documents.count()).isZero();
    }

    @Test void apiAcceptsInitialDocumentsAndAppliesNestedValidation() throws Exception {
        String ownerSession=demo.token(DemoRole.TAXPAYER,taxpayer.id);
        SecurityContextHolder.clearContext();

        mvc.perform(post("/api/v1/exemption-requests").header("X-Demo-Session",ownerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(List.of(document(" ","IDENTITY","invalido.pdf"))))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(requests.count()).isZero();

        mvc.perform(post("/api/v1/exemption-requests").header("X-Demo-Session",ownerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(List.of(document("DOC-API","IDENTITY","dni.pdf"))))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(requests.count()).isEqualTo(1);
        assertThat(documents.count()).isEqualTo(1);
    }

    @Test void documentsEndpointReturnsOnlyPublicFieldsInDeterministicOrder() throws Exception {
        ExemptionRequest created=exemptions.create(request(List.of(
            document("DOC-1","IDENTITY","dni.pdf"),
            document("DOC-2","CERTIFICATE","certificado.pdf"))));
        List<ExemptionRequestDocument> stored=documents.findByExemptionRequestId(created.id);
        OffsetDateTime sameInstant=OffsetDateTime.parse("2026-10-01T10:00:00-03:00");
        stored.forEach(document -> document.uploadedAt=sameInstant);
        documents.saveAllAndFlush(stored);
        String ownerSession=demo.token(DemoRole.TAXPAYER,taxpayer.id);
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/exemption-requests/{id}/documents",created.id).header("X-Demo-Session",ownerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].externalDocumentId").value("DOC-1"))
            .andExpect(jsonPath("$[1].externalDocumentId").value("DOC-2"))
            .andExpect(jsonPath("$[0].exemptionRequestId").value(created.id))
            .andExpect(jsonPath("$[0].uploadedBy").value("taxpayer-owner"))
            .andExpect(jsonPath("$[0].uploadedAt").exists())
            .andExpect(jsonPath("$[0].status").doesNotExist());
    }

    @Test void foreignTaxpayerCannotReadDocuments() throws Exception {
        ExemptionRequest created=exemptions.create(request(List.of(document("DOC-1","IDENTITY","dni.pdf"))));
        TaxpayerReference foreign=catalog.createTaxpayer(new ApiDtos.CreateTaxpayerRequest(TaxpayerType.CITIZEN,"EXEMPTION-DOCUMENTS-FOREIGN","30999877",null,"Contribuyente ajeno"));
        String foreignSession=demo.token(DemoRole.TAXPAYER,foreign.id);
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/exemption-requests/{id}/documents",created.id).header("X-Demo-Session",foreignSession))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
    }

    @ParameterizedTest
    @EnumSource(value=DemoRole.class,names={"RENTAS","AUDITOR","SUPERVISOR"})
    void authorizedStaffCanReadDocuments(DemoRole role) throws Exception {
        ExemptionRequest created=exemptions.create(request(List.of(document("DOC-1","IDENTITY","dni.pdf"))));
        String session=demo.token(role);
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/exemption-requests/{id}/documents",created.id).header("X-Demo-Session",session))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].externalDocumentId").value("DOC-1"));
    }

    @Test void nonexistentRequestReturnsNotFound() throws Exception {
        String session=demo.token(DemoRole.RENTAS);
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/v1/exemption-requests/{id}/documents",Long.MAX_VALUE).header("X-Demo-Session",session))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void requestedDocumentationStillReturnsRequestToUnderReview() {
        ExemptionRequest created=exemptions.create(request(null));
        exemptions.start(created.id);
        exemptions.requestDocumentation(created.id,"Adjuntar constancia");

        ExemptionRequestDocument stored=exemptions.submitDocumentation(created.id,document("DOC-LATER","CERTIFICATE","constancia.pdf"));

        assertThat(stored.exemptionRequestId).isEqualTo(created.id);
        assertThat(created.status).isEqualTo(ExemptionRequestStatus.UNDER_REVIEW);
        assertThat(created.resolutionReason).isNull();
    }

    private ApiDtos.CreateExemptionRequest request(List<ApiDtos.SubmitDocumentationRequest> initialDocuments) {
        return new ApiDtos.CreateExemptionRequest(taxpayer.id,concept.id,"Motivo documentado",new BigDecimal("50"),LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),initialDocuments);
    }

    private ApiDtos.SubmitDocumentationRequest document(String externalId,String type,String fileName) {
        return new ApiDtos.SubmitDocumentationRequest(externalId,type,fileName);
    }

    private void authenticate(AuthenticatedIdentity identity,String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
}
