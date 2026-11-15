package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test") @SpringBootTest @AutoConfigureMockMvc @Transactional
@org.springframework.test.context.jdbc.Sql(statements="INSERT INTO demo_bootstrap_lock(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM demo_bootstrap_lock WHERE id=1)")
class DashboardSecurityTests {
    @Autowired MockMvc mvc;
    @Autowired DemoUserRepository users;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoAuthService auth;
    private DemoAuthSessions sessions;

    @BeforeEach void setUp(){sessions=new DemoAuthSessions(users,taxpayers,encoder,auth);}

    @Test void supervisorOnlyReadsSupervisorSummary() throws Exception {
        mvc.perform(get("/api/v1/dashboards/supervisor").header("X-Demo-Session",sessions.token(DemoRole.SUPERVISOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalPending").isNumber()).andExpect(jsonPath("$.queues").isArray());
        mvc.perform(get("/api/v1/dashboards/supervisor").header("X-Demo-Session",sessions.token(DemoRole.RENTAS)))
            .andExpect(status().isForbidden());
    }

    @Test void rentasOnlyReadsOperationalSummary() throws Exception {
        mvc.perform(get("/api/v1/dashboards/rentas").header("X-Demo-Session",sessions.token(DemoRole.RENTAS)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.openTickets").isNumber())
            .andExpect(jsonPath("$.recentOperations").isArray());
        mvc.perform(get("/api/v1/dashboards/rentas").header("X-Demo-Session",sessions.token(DemoRole.TAXPAYER)))
            .andExpect(status().isForbidden());
    }
}
