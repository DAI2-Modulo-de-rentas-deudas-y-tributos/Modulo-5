package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "staging"})
@Transactional
@Sql(statements = "INSERT INTO demo_bootstrap_lock(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM demo_bootstrap_lock WHERE id=1)")
class StagingSecurityTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void stagingKeepsDemoLoginWithoutExposingDocsOrSimulators() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/dev-auth/bootstrap")
                .header("X-Demo-Bootstrap-Secret", "test-bootstrap-secret")
                .contentType("application/json")
                .content("""
                    {"username":"staging.supervisor","password":"clave-solo-fixture","displayName":"QA"}
                    """))
            .andExpect(status().isCreated());

        byte[] response = mvc.perform(post("/api/v1/dev-auth/login")
                .contentType("application/json")
                .content("""
                    {"username":"staging.supervisor","password":"clave-solo-fixture"}
                    """))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String session = json.readTree(response).required("token").asText();

        for (String path : new String[]{"/m2/events", "/m2/core-events", "/m4/core-events"}) {
            mvc.perform(post("/api/v1/dev/integrations" + path)
                    .header("X-Demo-Session", session)
                    .contentType("application/json")
                    .content("{}"))
                .andExpect(status().isNotFound());
        }
    }
}
