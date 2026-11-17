package ar.comprobantes.extractor.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.comprobantes.extractor.error.LlmLimiteTokensException;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import ar.comprobantes.extractor.error.LlmTimeoutException;
import ar.comprobantes.extractor.llm.LlmCliente;
import ar.comprobantes.extractor.llm.PromptLlm;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class ExtraccionControllerTest {

    private static final String LLM_OK = """
        {"cabecera":{"numeroComprobante":"0003-00012345"},"detalle":[]}
        """;
    private static final String SOLICITUD_OK =
        "{\"camposCabecera\":[{\"nombre\":\"numeroComprobante\",\"etiquetas\":[\"Factura\"]}]}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    LlmCliente llm;

    @BeforeEach
    void preparar() {
        when(llm.modelo()).thenReturn("modelo-falso");
        when(llm.completar(any(PromptLlm.class))).thenReturn(LLM_OK);
    }

    private MockHttpServletRequestBuilder peticion(byte[] doc, String solicitud) {
        return multipart("/api/v1/extracciones/sync")
            .file(new MockMultipartFile("documento", "f.pdf", "application/pdf", doc))
            .file(new MockMultipartFile("solicitud", "", "application/json",
                solicitud.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] pdf() throws Exception {
        return PdfsDePrueba.pdf("Factura 0003-00012345 de Razon Social de ejemplo sociedad anonima",
            "Total 15.000,00");
    }

    @Test
    void extraccionOk() throws Exception {
        mvc.perform(peticion(pdf(), SOLICITUD_OK))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cabecera.numeroComprobante.valor").exists())
            .andExpect(jsonPath("$.metadatos.modoProcesamiento").value("TEXTO"));
    }

    @Test
    void sinCamposEs400() throws Exception {
        mvc.perform(peticion(pdf(), "{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("La solicitud no contiene campos."));
    }

    @Test
    void nombreConEspaciosEs400() throws Exception {
        mvc.perform(peticion(pdf(), "{\"camposCabecera\":[{\"nombre\":\"número factura\"}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("no es válido")));
    }

    @Test
    void nombresDuplicadosEs400() throws Exception {
        mvc.perform(peticion(pdf(), "{\"camposCabecera\":[{\"nombre\":\"a\"},{\"nombre\":\"a\"}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("duplicado")));
    }

    @Test
    void parteFaltanteEs400() throws Exception {
        mvc.perform(multipart("/api/v1/extracciones/sync")
                .file(new MockMultipartFile("documento", "f.pdf", "application/pdf", pdf())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void jsonDeSolicitudInvalidoEs400() throws Exception {
        mvc.perform(peticion(pdf(), "{no es json"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void formatoNoSoportadoEs415() throws Exception {
        mvc.perform(peticion("texto plano".getBytes(StandardCharsets.UTF_8), SOLICITUD_OK))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.detail").value("El documento no es PDF, JPG ni PNG."));
    }

    @Test
    void ollamaCaidoEs503() throws Exception {
        when(llm.completar(any(PromptLlm.class))).thenThrow(new LlmNoDisponibleException("caído"));
        mvc.perform(peticion(pdf(), SOLICITUD_OK))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void limiteDeTokensEs422() throws Exception {
        String msg = "La respuesta del modelo superó el límite de tokens (EXTRACTOR_OLLAMA_NUMPREDICT).";
        when(llm.completar(any(PromptLlm.class))).thenThrow(new LlmLimiteTokensException(msg));
        mvc.perform(peticion(pdf(), SOLICITUD_OK))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.detail").value(msg));
    }

    @Test
    void timeoutEs504() throws Exception {
        when(llm.completar(any(PromptLlm.class))).thenThrow(new LlmTimeoutException("lento"));
        mvc.perform(peticion(pdf(), SOLICITUD_OK)).andExpect(status().isGatewayTimeout());
    }

    @Test
    void errorInesperadoEs500SinDetalleInterno() throws Exception {
        when(llm.completar(any(PromptLlm.class))).thenThrow(new RuntimeException("secreto"));
        mvc.perform(peticion(pdf(), SOLICITUD_OK))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("Error interno."))
            .andExpect(content().string(not(containsString("secreto"))));
    }

    @Test
    void getSobreSyncEs405() throws Exception {
        mvc.perform(get("/api/v1/extracciones/sync"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("Método no permitido."));
    }

    @Test
    void postJsonEs415() throws Exception {
        mvc.perform(post("/api/v1/extracciones/sync").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void urlDesconocidaEs404() throws Exception {
        mvc.perform(get("/no-existe"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
