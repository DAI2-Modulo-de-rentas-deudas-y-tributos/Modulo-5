package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Las respuestas serializan como null las propiedades opcionales sin valor: el contrato tiene que admitirlo.
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiNullabilityTests {
    @Autowired MockMvc mvc;

    @Test
    void lasPropiedadesOpcionalesAdmitenNullYLasRequeridasNo() throws Exception {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.schemas.ErrorResponse.properties.details.type", hasItem("null")))
            .andExpect(jsonPath("$.paths['/api/v1/administration/process-due-dates'].post.responses['202']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/debts'].get.responses['415']").exists());
    }

    @Test
    void unaPropiedadRequeridaNoSeVuelveNullable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.schemas.RejectionRequest.required", hasItem("reason")))
            .andExpect(jsonPath("$.components.schemas.RejectionRequest.properties.reason.type", not(hasItem("null"))));
    }
}
