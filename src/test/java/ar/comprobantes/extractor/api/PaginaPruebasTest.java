package ar.comprobantes.extractor.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.comprobantes.extractor.llm.LlmCliente;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PaginaPruebasTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    LlmCliente llm;

    @Test
    void paginaSinBarra() throws Exception {
        mvc.perform(get("/pruebas")).andExpect(status().isOk())
            .andExpect(forwardedUrl("/pruebas/index.html"));
    }

    @Test
    void paginaConBarra() throws Exception {
        mvc.perform(get("/pruebas/")).andExpect(status().isOk())
            .andExpect(forwardedUrl("/pruebas/index.html"));
    }

    @Test
    void indexSeSirveYReferenciaRecursosLocales() throws Exception {
        mvc.perform(get("/pruebas/index.html")).andExpect(status().isOk())
            .andExpect(content().string(containsString("/pruebas/pruebas.js")))
            .andExpect(content().string(containsString("/pruebas/pruebas.css")))
            .andExpect(content().string(not(containsString("http://"))))
            .andExpect(content().string(not(containsString("https://"))));
    }

    @Test
    void scriptSeSirve() throws Exception {
        mvc.perform(get("/pruebas/pruebas.js")).andExpect(status().isOk());
    }

    @Test
    void apiSigueIgual() throws Exception {
        // la configuración nueva no captura rutas de la API
        mvc.perform(get("/api/v1/extracciones/sync")).andExpect(status().isMethodNotAllowed());
    }
}
