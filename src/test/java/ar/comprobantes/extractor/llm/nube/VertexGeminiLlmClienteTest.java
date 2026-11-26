package ar.comprobantes.extractor.llm.nube;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.api.dto.TipoCampo;
import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.LlmCuotaAgotadaException;
import ar.comprobantes.extractor.error.LlmLimiteTokensException;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import ar.comprobantes.extractor.error.LlmTimeoutException;
import ar.comprobantes.extractor.error.RespuestaLlmInvalidaException;
import ar.comprobantes.extractor.llm.EsquemaRespuesta;
import ar.comprobantes.extractor.llm.PromptLlm;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class VertexGeminiLlmClienteTest {

    private static final String RUTA =
        "/v1/projects/mi-proyecto/locations/us-central1/publishers/google/models/gemini-2.5-flash:generateContent";
    private static final String MSG_INVALIDA = "El proveedor de IA no devolvió una respuesta utilizable.";

    private WireMockServer servidor;

    @BeforeEach
    void iniciar() {
        servidor = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        servidor.start();
    }

    @AfterEach
    void detener() {
        servidor.stop();
    }

    private VertexGeminiLlmCliente cliente(Duration timeout, Integer presupuesto, ProveedorToken token) {
        var vertex = new ExtractorProperties.Vertex("mi-proyecto", "us-central1", "gemini-2.5-flash", null, timeout,
            4096, presupuesto);
        var props = new ExtractorProperties(null, null, null, vertex, null,
            new ExtractorProperties.Concurrencia(1, Duration.ofSeconds(60)),
            new ExtractorProperties.Documento(10, 150, 200, 2000, 50),
            new ExtractorProperties.Debug(false));
        return new VertexGeminiLlmCliente(props, token, servidor.baseUrl());
    }

    private VertexGeminiLlmCliente cliente() {
        return cliente(Duration.ofSeconds(5), null, () -> "tok-1");
    }

    private PromptLlm prompt(List<String> imagenes) {
        var esquema = new EsquemaRespuesta().para(new SolicitudExtraccion(
            List.of(new CampoSolicitado("numeroComprobante", List.of(), null, TipoCampo.TEXTO)),
            List.of(new CampoSolicitado("descripcion", List.of(), null, TipoCampo.TEXTO))), false);
        return new PromptLlm("sistema", "usuario", imagenes, esquema);
    }

    private void stubRespuesta(String json) {
        servidor.stubFor(post(urlEqualTo(RUTA)).willReturn(okJson(json)));
    }

    private void stubOk() {
        stubRespuesta("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{\\\"cabecera\\\":{}}\"}]}}],"
            + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5,\"thoughtsTokenCount\":3}}");
    }

    @Test
    void urlRegionalYGlobal() {
        assertThat(VertexGeminiLlmCliente.urlBase("us-central1")).isEqualTo("https://us-central1-aiplatform.googleapis.com");
        assertThat(VertexGeminiLlmCliente.urlBase("global")).isEqualTo("https://aiplatform.googleapis.com");
        assertThat(VertexGeminiLlmCliente.urlBase("GLOBAL")).isEqualTo("https://aiplatform.googleapis.com");
    }

    @Test
    void modeloEsElConfigurado() {
        assertThat(cliente().modelo()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void enviaCuerpoEsperado() {
        stubOk();
        assertThat(cliente().completar(prompt(List.of("AAAA")))).isEqualTo("{\"cabecera\":{}}");
        servidor.verify(postRequestedFor(urlEqualTo(RUTA))
            .withHeader("Authorization", equalTo("Bearer tok-1"))
            .withRequestBody(matchingJsonPath("$.systemInstruction.parts[0].text", equalTo("sistema")))
            .withRequestBody(matchingJsonPath("$.contents[0].role", equalTo("user")))
            .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text", equalTo("usuario")))
            .withRequestBody(matchingJsonPath("$.contents[0].parts[1].inlineData.mimeType", equalTo("image/png")))
            .withRequestBody(matchingJsonPath("$.contents[0].parts[1].inlineData.data", equalTo("AAAA")))
            .withRequestBody(matchingJsonPath("$.generationConfig.temperature", equalTo("0")))
            .withRequestBody(matchingJsonPath("$.generationConfig.maxOutputTokens", equalTo("4096")))
            .withRequestBody(matchingJsonPath("$.generationConfig.responseMimeType", equalTo("application/json")))
            .withRequestBody(matchingJsonPath("$.generationConfig.responseSchema.type", equalTo("OBJECT")))
            .withRequestBody(matchingJsonPath("$.generationConfig.responseSchema.propertyOrdering[0]",
                equalTo("cabecera")))
            .withRequestBody(matchingJsonPath(
                "$.generationConfig.responseSchema.properties.cabecera.properties.numeroComprobante.nullable",
                equalTo("true")))
            .withRequestBody(notMatching("(?s).*thinkingConfig.*")));
    }

    @Test
    void presupuestoRazonamientoSeEnvia() {
        stubOk();
        cliente(Duration.ofSeconds(5), 0, () -> "tok-1").completar(prompt(List.of()));
        servidor.verify(postRequestedFor(urlEqualTo(RUTA))
            .withRequestBody(matchingJsonPath("$.generationConfig.thinkingConfig.thinkingBudget", equalTo("0"))));
    }

    @Test
    void concatenaPartesIgnorandoThought() {
        stubRespuesta("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":["
            + "{\"text\":\"pensando\",\"thought\":true},{\"text\":\"{\\\"a\\\":\"},{\"text\":\"1}\"}]}}]}");
        assertThat(cliente().completar(prompt(List.of()))).isEqualTo("{\"a\":1}");
    }

    @Test
    void maxTokensEs422() {
        stubRespuesta("{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"{\"}]}}]}");
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOf(LlmLimiteTokensException.class)
            .hasMessage("La respuesta del modelo superó el límite de tokens (EXTRACTOR_VERTEX_MAXTOKENS).");
        servidor.verify(1, postRequestedFor(urlEqualTo(RUTA)));
    }

    @Test
    void promptBloqueadoEs422() {
        stubRespuesta("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOf(RespuestaLlmInvalidaException.class).hasMessage(MSG_INVALIDA);
    }

    @Test
    void safetyEs422() {
        stubRespuesta("{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}");
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOf(RespuestaLlmInvalidaException.class).hasMessage(MSG_INVALIDA);
    }

    @Test
    void soloThoughtEs422() {
        stubRespuesta("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":["
            + "{\"text\":\"pensando\",\"thought\":true}]}}]}");
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOf(RespuestaLlmInvalidaException.class).hasMessage(MSG_INVALIDA);
    }

    @Test
    void sinCandidatosEs422() {
        stubRespuesta("{\"candidates\":[]}");
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOf(RespuestaLlmInvalidaException.class).hasMessage(MSG_INVALIDA);
    }

    @Test
    void cuota429Es503ConRetryAfter() {
        servidor.stubFor(post(urlEqualTo(RUTA)).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "7")));
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOfSatisfying(LlmCuotaAgotadaException.class,
                e -> assertThat(e.retryAfterSegundos()).isEqualTo(7L));
        servidor.verify(1, postRequestedFor(urlEqualTo(RUTA)));
    }

    @Test
    void tokenFallaEs503() {
        var c = cliente(Duration.ofSeconds(5), null, () -> {
            throw new LlmNoDisponibleException("El servicio de IA no está disponible.");
        });
        assertThatThrownBy(() -> c.completar(prompt(List.of()))).isInstanceOf(LlmNoDisponibleException.class);
        servidor.verify(0, postRequestedFor(urlEqualTo(RUTA)));
    }

    @Test
    void timeoutEs504() {
        servidor.stubFor(post(urlEqualTo(RUTA)).willReturn(okJson("{}").withFixedDelay(2000)));
        var c = cliente(Duration.ofMillis(500), null, () -> "tok-1");
        assertThatThrownBy(() -> c.completar(prompt(List.of()))).isInstanceOf(LlmTimeoutException.class);
    }

    @Test
    void logsSinTokenNiContenido(CapturedOutput salida) {
        stubRespuesta("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"SECRETO\"}]}}],"
            + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5,\"thoughtsTokenCount\":3}}");
        cliente(Duration.ofSeconds(5), null, () -> "TOKEN-SECRETO").completar(prompt(List.of()));
        assertThat(salida.getAll()).contains("Vertex Gemini completó").contains("promptTokenCount=10")
            .doesNotContain("SECRETO").doesNotContain("TOKEN-SECRETO");
    }
}
