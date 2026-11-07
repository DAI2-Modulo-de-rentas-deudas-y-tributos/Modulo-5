package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("postgres-it")
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.transaction.annotation.Transactional
@Testcontainers(disabledWithoutDocker=true)
class PaymentPlanRequestEtagHttpTests {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/migration-postgresql,classpath:db/migration-demo");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PaymentPlanRequestRepository requests;
    @Autowired TaxpayerRepository taxpayers;

    @Test void stalePreconditionDoesNotChangeRequestAndFreshTagTracksEachTransition() throws Exception {
        PaymentPlanRequest request=request();
        String path="/api/v1/payment-plan-requests/"+request.id;
        var rentas=actor("rentas-plan-etag","RENTAS");
        var supervisor=actor("supervisor-plan-etag","SUPERVISOR");

        var initial=mvc.perform(get(path).with(authentication(rentas))).andExpect(status().isOk()).andReturn().getResponse();
        String initialTag=initial.getHeader("ETag");
        assertThat(initialTag).isNotBlank();

        mvc.perform(post(path+"/submit-exception").with(authentication(rentas)).header("If-Match","\"stale\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"review\"}"))
            .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        assertThat(requests.findById(request.id).orElseThrow().status).isEqualTo(PaymentPlanRequestStatus.PENDING);
        assertThat(requests.findById(request.id).orElseThrow().exceptionSubmittedBy).isNull();

        var submitted=mvc.perform(post(path+"/submit-exception").with(authentication(rentas)).header("If-Match",initialTag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"review\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_EXCEPTION_APPROVAL"))
            .andReturn().getResponse();
        String submittedTag=submitted.getHeader("ETag");
        assertThat(submittedTag).isNotBlank().isNotEqualTo(initialTag);

        mvc.perform(post(path+"/approve-exception").with(authentication(supervisor)).header("If-Match",initialTag))
            .andExpect(status().isPreconditionFailed());
        assertThat(requests.findById(request.id).orElseThrow().status).isEqualTo(PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL);
        assertThat(requests.findById(request.id).orElseThrow().exceptionApproved).isFalse();

        var approved=mvc.perform(post(path+"/approve-exception").with(authentication(supervisor)).header("If-Match",submittedTag))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn().getResponse();
        assertThat(approved.getHeader("ETag")).isNotBlank().isNotEqualTo(submittedTag);

        JsonNode openApi=json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode approve=openApi.at("/paths/~1api~1v1~1payment-plan-requests~1{id}~1approve-exception/post");
        assertThat(approve.path("responses").has("412")).isTrue();
        assertThat(approve.path("responses").path("200").path("headers").has("ETag")).isTrue();
        assertThat(approve.path("parameters").findValuesAsText("name")).contains("If-Match");
        JsonNode ifMatch=null;
        for(JsonNode parameter:approve.path("parameters"))if(parameter.path("name").asText().equals("If-Match"))ifMatch=parameter;
        assertThat(ifMatch).isNotNull();
        assertThat(ifMatch.path("required").asBoolean()).isFalse();
        assertThat(openApi.at("/paths/~1api~1v1~1payment-plan-requests~1{id}/get/responses/200/headers").has("ETag")).isTrue();
    }

    @Test void ifMatchParticipatesInIdempotencyFingerprint() throws Exception {
        PaymentPlanRequest request=request();
        String path="/api/v1/payment-plan-requests/"+request.id;
        var rentas=actor("rentas-plan-etag-key","RENTAS");
        String currentTag=mvc.perform(get(path).with(authentication(rentas))).andReturn().getResponse().getHeader("ETag");

        mvc.perform(post(path+"/submit-exception").with(authentication(rentas)).header("If-Match",currentTag)
                .header("Idempotency-Key","plan-submit-"+request.id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"review\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_EXCEPTION_APPROVAL"));

        mvc.perform(post(path+"/submit-exception").with(authentication(rentas)).header("If-Match",currentTag)
                .header("Idempotency-Key","plan-submit-"+request.id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"review\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.response").doesNotExist())
            .andExpect(jsonPath("$.status").value("PENDING_EXCEPTION_APPROVAL"));

        mvc.perform(post(path+"/submit-exception").with(authentication(rentas)).header("If-Match","\"different\"")
                .header("Idempotency-Key","plan-submit-"+request.id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"review\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(requests.findById(request.id).orElseThrow().status).isEqualTo(PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL);
    }

    @Test void concurrentTransitionsWithTheSameEtagAllowOnlyOneUpdate() throws Exception {
        PaymentPlanRequest request=request();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        String path="/api/v1/payment-plan-requests/"+request.id;
        var rentas=actor("rentas-plan-etag-race","RENTAS");
        String tag=mvc.perform(get(path).with(authentication(rentas))).andReturn().getResponse().getHeader("ETag");
        CountDownLatch start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{start.await();return mvc.perform(post(path+"/submit-exception").with(authentication(rentas)).header("If-Match",tag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"review\"}")).andReturn().getResponse().getStatus();});
            var second=pool.submit(()->{start.await();return mvc.perform(post(path+"/submit-exception").with(authentication(rentas)).header("If-Match",tag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"review\"}")).andReturn().getResponse().getStatus();});
            start.countDown();
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
        } finally { pool.shutdownNow(); }
        assertThat(requests.findById(request.id).orElseThrow().status).isEqualTo(PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL);
    }

    @Test void bothRejectionActionsAcceptFreshTagsAndReturnUpdatedRepresentations() throws Exception {
        var supervisor=actor("supervisor-plan-reject-etag","SUPERVISOR");
        PaymentPlanRequest ordinary=request();
        String ordinaryPath="/api/v1/payment-plan-requests/"+ordinary.id;
        String ordinaryTag=mvc.perform(get(ordinaryPath).with(authentication(supervisor))).andReturn().getResponse().getHeader("ETag");
        var ordinaryRejected=mvc.perform(post(ordinaryPath+"/reject").with(authentication(supervisor)).header("If-Match",ordinaryTag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"insufficient documentation\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
            .andReturn().getResponse();
        assertThat(ordinaryRejected.getHeader("ETag")).isNotBlank().isNotEqualTo(ordinaryTag);
        assertThat(requests.findById(ordinary.id).orElseThrow().resolvedBy).isEqualTo("supervisor-plan-reject-etag");

        PaymentPlanRequest exceptional=request();
        exceptional.status=PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL;
        exceptional.exceptionSubmittedBy="rentas-plan-exception";
        requests.saveAndFlush(exceptional);
        String exceptionalPath="/api/v1/payment-plan-requests/"+exceptional.id;
        String exceptionalTag=mvc.perform(get(exceptionalPath).with(authentication(supervisor))).andReturn().getResponse().getHeader("ETag");
        var exceptionRejected=mvc.perform(post(exceptionalPath+"/reject-exception").with(authentication(supervisor)).header("If-Match",exceptionalTag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"exception not justified\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
            .andReturn().getResponse();
        assertThat(exceptionRejected.getHeader("ETag")).isNotBlank().isNotEqualTo(exceptionalTag);
        assertThat(requests.findById(exceptional.id).orElseThrow().resolvedBy).isEqualTo("supervisor-plan-reject-etag");
    }

    private PaymentPlanRequest request(){
        TaxpayerReference taxpayer=new TaxpayerReference();
        taxpayer.taxpayerType=TaxpayerType.CITIZEN;taxpayer.externalId="ETAG-PLAN-"+java.util.UUID.randomUUID();
        taxpayer.displayName="ETag plan request";taxpayer.externalStatus=TaxpayerStatus.ACTIVE;
        taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();
        taxpayer=taxpayers.saveAndFlush(taxpayer);
        PaymentPlanRequest request=new PaymentPlanRequest();
        request.taxpayerId=taxpayer.id;request.requestedInstallments=3;request.totalDebtAtRequest=new BigDecimal("100.00");
        request.estimatedDownPayment=new BigDecimal("10.00");request.estimatedFinancedAmount=new BigDecimal("90.00");
        request.estimatedInterest=BigDecimal.ZERO;request.estimatedTotalAmount=new BigDecimal("100.00");
        request.exceptional=true;request.exceptionApproved=false;request.status=PaymentPlanRequestStatus.PENDING;
        request.requestedBy="taxpayer-plan-request";request.requestedAt=OffsetDateTime.now();
        return requests.saveAndFlush(request);
    }

    private UsernamePasswordAuthenticationToken actor(String user,String role){
        return new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(user,null,null),null,
            List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
}
