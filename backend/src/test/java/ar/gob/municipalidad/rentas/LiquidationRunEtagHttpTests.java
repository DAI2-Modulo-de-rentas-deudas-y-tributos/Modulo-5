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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
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
@Transactional
@Testcontainers(disabledWithoutDocker=true)
class LiquidationRunEtagHttpTests {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/migration-postgresql,classpath:db/migration-demo");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired LiquidationRunRepository runs;
    @Autowired LiquidationRunItemRepository items;

    @Test void freshEtagAllowsTransitionAndStaleEtagRejectsWithoutChangingRun() throws Exception {
        LiquidationRun run=run();
        var rentas=actor("rentas-etag", "RENTAS");
        var supervisor=actor("supervisor-etag", "SUPERVISOR");
        String path="/api/v1/liquidation-runs/"+run.id;

        var get=mvc.perform(get(path).with(authentication(rentas)))
            .andExpect(status().isOk()).andReturn().getResponse();
        String initialTag=get.getHeader("ETag");
        assertThat(initialTag).isNotBlank();

        var submitted=mvc.perform(post(path+"/submit").with(authentication(rentas)).header("If-Match",initialTag)
                .header("Idempotency-Key","submit-"+run.id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
            .andReturn().getResponse();
        String submittedTag=submitted.getHeader("ETag");
        assertThat(submittedTag).isNotBlank().isNotEqualTo(initialTag);

        mvc.perform(post(path+"/approve").with(authentication(supervisor)).header("If-Match",initialTag))
            .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        assertThat(runs.findById(run.id).orElseThrow().status).isEqualTo(LiquidationRunStatus.PENDING_APPROVAL);
        assertThat(runs.findById(run.id).orElseThrow().resolvedBy).isNull();

        var approved=mvc.perform(post(path+"/approve").with(authentication(supervisor)).header("If-Match",submittedTag)
                .header("Idempotency-Key","approve-"+run.id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
            .andReturn().getResponse();
        assertThat(approved.getHeader("ETag")).isNotBlank().isNotEqualTo(submittedTag);
    }

    @Test void ifMatchParticipatesInIdempotencyFingerprintAndOpenApiDocumentsPolicy() throws Exception {
        LiquidationRun run=run();
        var rentas=actor("rentas-etag-key", "RENTAS");
        String path="/api/v1/liquidation-runs/"+run.id;
        String originalTag=mvc.perform(get(path).with(authentication(rentas))).andReturn().getResponse().getHeader("ETag");

        mvc.perform(post(path+"/submit").with(authentication(rentas)).header("If-Match",originalTag)
                .header("Idempotency-Key","same-key-"+run.id))
            .andExpect(status().isOk());

        mvc.perform(post(path+"/submit").with(authentication(rentas)).header("If-Match","\"different-tag\"")
                .header("Idempotency-Key","same-key-"+run.id))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        JsonNode openApi=json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode submit=openApi.at("/paths/~1api~1v1~1liquidation-runs~1{id}~1submit/post");
        assertThat(submit.path("parameters").findValuesAsText("name")).contains("If-Match");
        assertThat(submit.path("responses").has("412")).isTrue();
        assertThat(submit.path("responses").path("200").path("headers").has("ETag")).isTrue();
        JsonNode ifMatch=null;
        for(JsonNode parameter:submit.path("parameters"))if(parameter.path("name").asText().equals("If-Match"))ifMatch=parameter;
        assertThat(ifMatch).isNotNull();
        assertThat(ifMatch.path("required").asBoolean()).isFalse();
    }

    @Test void concurrentSubmissionsWithTheSameEtagAllowOnlyOneTransition() throws Exception {
        LiquidationRun run=run();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        String path="/api/v1/liquidation-runs/"+run.id;
        var rentas=actor("rentas-etag-concurrent", "RENTAS");
        String tag=mvc.perform(get(path).with(authentication(rentas))).andReturn().getResponse().getHeader("ETag");
        assertThat(tag).isNotBlank();

        CountDownLatch start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->{start.await();return mvc.perform(post(path+"/submit").with(authentication(rentas)).header("If-Match",tag)).andReturn().getResponse().getStatus();});
            var second=pool.submit(()->{start.await();return mvc.perform(post(path+"/submit").with(authentication(rentas)).header("If-Match",tag)).andReturn().getResponse().getStatus();});
            start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
        } finally { pool.shutdownNow(); }
        assertThat(runs.findById(run.id).orElseThrow().status).isEqualTo(LiquidationRunStatus.PENDING_APPROVAL);
    }

    private LiquidationRun run(){
        LiquidationRun run=new LiquidationRun();run.taxConceptId=1L;run.period="2026-11";run.dueDate=java.time.LocalDate.now().plusDays(30);
        run.status=LiquidationRunStatus.DRAFT;run.totalItems=1;run.validItems=1;run.errorItems=0;run.estimatedTotalAmount=new BigDecimal("25.00");
        run.createdBy="rentas-etag";run.createdAt=OffsetDateTime.now();runs.saveAndFlush(run);
        LiquidationRunItem item=new LiquidationRunItem();item.liquidationRunId=run.id;item.taxpayerId=1L;item.taxableBase=new BigDecimal("100.00");
        item.previewAmount=new BigDecimal("25.00");item.status=LiquidationRunItemStatus.VALID;items.saveAndFlush(item);return run;
    }

    private UsernamePasswordAuthenticationToken actor(String user,String role){
        return new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(user,null,null),null,
            List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
}
