package ar.comprobantes.extractor.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.comprobantes.extractor.llm.LlmCliente;
import ar.comprobantes.extractor.llm.PromptLlm;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LogsSinContenidoTest {

    private static final String SECRETO = "CLIENTE-SECRETO-XYZ";
    private static final String SOLICITUD =
        "{\"camposCabecera\":[{\"nombre\":\"cliente\"},{\"nombre\":\"cuit\",\"tipo\":\"CUIT\"}]}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    LlmCliente llm;

    private MockHttpServletRequestBuilder peticion() throws Exception {
        byte[] pdf = PdfsDePrueba.pdf("Factura 0003-00012345 de Razon Social de ejemplo sociedad anonima",
            "Cliente " + SECRETO, "CUIT 30-71234567-1");
        return multipart("/api/v1/extracciones/sync")
            .file(new MockMultipartFile("documento", "f.pdf", "application/pdf", pdf))
            .file(new MockMultipartFile("solicitud", "", "application/json",
                SOLICITUD.getBytes(StandardCharsets.UTF_8)));
    }

    private static void sinSecretos(CapturedOutput salida) {
        assertThat(salida.getAll()).doesNotContain(SECRETO).doesNotContain("30712345671")
            .doesNotContain("30-71234567-1");
    }

    @Test
    void logsNoContienenDatosDelDocumento(CapturedOutput salida) throws Exception {
        when(llm.modelo()).thenReturn("modelo-falso");
        when(llm.completar(any(PromptLlm.class))).thenReturn("""
            {"cabecera":{"cliente":{"valor":"CLIENTE-SECRETO-XYZ","descripcion":null,"clavesQr":[]},
                         "cuit":{"valor":"30-71234567-1","descripcion":null,"clavesQr":[]}},"detalle":[]}
            """);
        mvc.perform(peticion()).andExpect(status().isOk());
        assertThat(salida.getAll()).contains("Extracción completada");
        sinSecretos(salida);
    }

    @Test
    void logsNoContienenDatosCuandoLlmDevuelveJsonInvalido(CapturedOutput salida) throws Exception {
        when(llm.modelo()).thenReturn("modelo-falso");
        when(llm.completar(any(PromptLlm.class)))
            .thenReturn("{\"cliente\": CLIENTE-SECRETO-XYZ 30712345671 [[");
        mvc.perform(peticion()).andExpect(status().isUnprocessableEntity());
        assertThat(salida.getAll()).contains("Solicitud rechazada");
        sinSecretos(salida);
    }

    @Test
    void errorInesperadoLogueaClasesSinMensaje(CapturedOutput salida) throws Exception {
        when(llm.modelo()).thenReturn("modelo-falso");
        when(llm.completar(any(PromptLlm.class))).thenThrow(new IllegalStateException("secreto-" + SECRETO));
        mvc.perform(peticion()).andExpect(status().isInternalServerError());
        assertThat(salida.getAll()).contains("Error interno inesperado").contains("IllegalStateException")
            .contains("ExtraccionService").doesNotContain("secreto-");
        sinSecretos(salida);
    }
}
