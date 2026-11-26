package ar.comprobantes.extractor.llm.nube;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.LlmCuotaAgotadaException;
import ar.comprobantes.extractor.error.LlmLimiteTokensException;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import ar.comprobantes.extractor.error.LlmTimeoutException;
import ar.comprobantes.extractor.error.RespuestaLlmInvalidaException;
import ar.comprobantes.extractor.llm.PromptLlm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
class AzureOpenAiLlmClienteTest {

    private static final String RUTA = "/openai/deployments/gpt-4o/chat/completions";
    private static final String RUTA_CON_VERSION = RUTA + "?api-version=2024-10-21";
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

    private AzureOpenAiLlmCliente cliente(Duration timeout, boolean razonamiento) {
        var azure = new ExtractorProperties.Azure(servidor.baseUrl(), "gpt-4o", "k", "2024-10-21", timeout, 4096,
            razonamiento);
        var props = new ExtractorProperties(null, null, azure, null, null,
            new ExtractorProperties.Concurrencia(1, Duration.ofSeconds(60)),
            new ExtractorProperties.Documento(10, 150, 200, 2000, 50),
            new ExtractorProperties.Debug(false));
        return new AzureOpenAiLlmCliente(props);
    }

    private AzureOpenAiLlmCliente cliente() {
        return cliente(Duration.ofSeconds(5), false);
    }

    private PromptLlm prompt(List<String> imagenes) {
        ObjectNode esquema = new ObjectMapper().createObjectNode().put("type", "object");
        return new PromptLlm("sistema", "usuario", imagenes, esquema);
    }

    private void stubRespuesta(String json) {
        servidor.stubFor(post(urlEqualTo(RUTA_CON_VERSION)).willReturn(okJson(json)));
    }

    private void stubOk() {
        stubRespuesta("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"cabecera\\\":{}}\"}}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
    }

    @Test
    void enviaCuerpoEsperado() {
        stubOk();
        cliente().completar(prompt(List.of("AAAA")));
        servidor.verify(postRequestedFor(urlEqualTo(RUTA_CON_VERSION))
            .withHeader("api-key", equalTo("k"))
            .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_schema")))
            .withRequestBody(matchingJsonPath("$.response_format.json_schema.name", equalTo("extraccion")))
            .withRequestBody(matchingJsonPath("$.response_format.json_schema.strict", equalTo("true")))
            .withRequestBody(matchingJsonPath("$.temperature", equalTo("0")))
            .withRequestBody(matchingJsonPath("$.max_completion_tokens", equalTo("4096")))
            .withRequestBody(notMatching("(?s).*reasoning_effort.*"))
            .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
            .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("sistema")))
            .withRequestBody(matchingJsonPath("$.messages[1].content[0].text", equalTo("usuario")))
            .withRequestBody(matchingJsonPath("$.messages[1].content[1].image_url.url",
                matching("data:image/png;base64,AAAA.*"))));
    }

    @Test
    void razonamientoOmiteTemperatura() {
        stubOk();
        cliente(Duration.ofSeconds(5), true).completar(prompt(List.of()));
        servidor.verify(postRequestedFor(urlEqualTo(RUTA_CON_VERSION))
            .withRequestBody(matchingJsonPath("$.reasoning_effort", equalTo("low")))
            .withRequestBody(notMatching("(?s).*\"temperature\".*")));
    }

    @Test
    void devuelveContenido() {
        stubOk();
        assertThat(cliente().completar(prompt(List.of()))).isEqualTo("{\"cabecera\":{}}");
    }

    @Test
    void modeloEsElDeployment() {
        assertThat(cliente().modelo()).isEqualTo("gpt-4o");
    }

    @Test
    void lengthEs422SinReintento() {
        stubRespuesta("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\"}}]}");
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOf(LlmLimiteTokensException.class)
            .hasMessage("La respuesta del modelo superó el límite de tokens (EXTRACTOR_AZURE_MAXTOKENS).");
        servidor.verify(1, postRequestedFor(urlEqualTo(RUTA_CON_VERSION)));
    }

    @Test
    void contentFilterEs422() {
        stubRespuesta("{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":null}}]}");
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOf(RespuestaLlmInvalidaException.class).hasMessage(MSG_INVALIDA);
    }

    @Test
    void refusalEs422() {
        stubRespuesta("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,"
            + "\"refusal\":\"no puedo\"}}]}");
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOf(RespuestaLlmInvalidaException.class).hasMessage(MSG_INVALIDA);
    }

    @Test
    void contentNullEs422() {
        stubRespuesta("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}");
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOf(RespuestaLlmInvalidaException.class).hasMessage(MSG_INVALIDA);
    }

    @Test
    void sinChoicesEs422() {
        stubRespuesta("{\"choices\":[]}");
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOf(RespuestaLlmInvalidaException.class).hasMessage(MSG_INVALIDA);
    }

    @Test
    void cuota429Es503ConRetryAfter() {
        servidor.stubFor(post(urlEqualTo(RUTA_CON_VERSION)).willReturn(aResponse().withStatus(429)
            .withHeader("Retry-After", "7")));
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOfSatisfying(LlmCuotaAgotadaException.class,
                e -> assertThat(e.retryAfterSegundos()).isEqualTo(7L));
        servidor.verify(1, postRequestedFor(urlEqualTo(RUTA_CON_VERSION)));
    }

    @Test
    void credenciales401Es503() {
        servidor.stubFor(post(urlEqualTo(RUTA_CON_VERSION)).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> cliente().completar(prompt(List.of())))
            .isInstanceOf(LlmNoDisponibleException.class);
    }

    @Test
    void timeoutEs504() {
        servidor.stubFor(post(urlEqualTo(RUTA_CON_VERSION)).willReturn(okJson("{}").withFixedDelay(2000)));
        var c = cliente(Duration.ofMillis(500), false);
        assertThatThrownBy(() -> c.completar(prompt(List.of()))).isInstanceOf(LlmTimeoutException.class);
    }

    @Test
    void logsSinKeyNiContenido(CapturedOutput salida) {
        var azure = new ExtractorProperties.Azure(servidor.baseUrl(), "gpt-4o", "KEY-SECRETA", "2024-10-21",
            Duration.ofSeconds(5), 4096, false);
        var props = new ExtractorProperties(null, null, azure, null, null,
            new ExtractorProperties.Concurrencia(1, Duration.ofSeconds(60)),
            new ExtractorProperties.Documento(10, 150, 200, 2000, 50),
            new ExtractorProperties.Debug(false));
        stubRespuesta("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"SECRETO\"}}]}");
        new AzureOpenAiLlmCliente(props).completar(prompt(List.of()));
        assertThat(salida.getAll()).contains("Azure OpenAI completó").doesNotContain("SECRETO")
            .doesNotContain("KEY-SECRETA");
    }
}
