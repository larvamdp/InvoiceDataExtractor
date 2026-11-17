package ar.comprobantes.extractor.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.LlmLimiteTokensException;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import ar.comprobantes.extractor.error.LlmTimeoutException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class OllamaLlmClienteTest {

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

    private OllamaLlmCliente cliente(String url, Duration timeout, Boolean think) {
        var props = new ExtractorProperties(
            new ExtractorProperties.Ollama(url, "qwen3-vl:4b", timeout, 8192, think, 2048),
            new ExtractorProperties.Concurrencia(1, Duration.ofSeconds(60)),
            new ExtractorProperties.Documento(10, 150, 200, 2000, 50),
            new ExtractorProperties.Debug(false));
        return new OllamaLlmCliente(props);
    }

    private PromptLlm prompt(List<String> imagenes) {
        ObjectNode esquema = new ObjectMapper().createObjectNode().put("type", "object");
        return new PromptLlm("sistema", "usuario", imagenes, esquema);
    }

    private void stubOk() {
        servidor.stubFor(post("/api/chat").willReturn(okJson(
            "{\"message\":{\"content\":\"{\\\"cabecera\\\":{}}\"},\"eval_count\":12}")));
    }

    @Test
    void enviaCuerpoEsperado() {
        stubOk();
        cliente(servidor.baseUrl(), Duration.ofSeconds(5), null).completar(prompt(List.of("AAAA")));
        servidor.verify(postRequestedFor(urlEqualTo("/api/chat"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("qwen3-vl:4b")))
            .withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
            .withRequestBody(matchingJsonPath("$.format.type", equalTo("object")))
            .withRequestBody(matchingJsonPath("$.options.temperature", equalTo("0")))
            .withRequestBody(matchingJsonPath("$.options.num_ctx", equalTo("8192")))
            .withRequestBody(matchingJsonPath("$.options.num_predict", equalTo("2048")))
            .withRequestBody(notMatching("(?s).*(repeat_penalty|presence_penalty).*"))
            .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
            .withRequestBody(matchingJsonPath("$.messages[1].images[0]", equalTo("AAAA")))
            .withRequestBody(notMatching("(?s).*\"think\".*")));
    }

    @Test
    void incluyeThinkSoloSiConfigurado() {
        stubOk();
        cliente(servidor.baseUrl(), Duration.ofSeconds(5), false).completar(prompt(List.of()));
        servidor.verify(postRequestedFor(urlEqualTo("/api/chat"))
            .withRequestBody(matchingJsonPath("$.think", equalTo("false"))));
    }

    @Test
    void omiteImagesSiNoHay() {
        stubOk();
        cliente(servidor.baseUrl(), Duration.ofSeconds(5), null).completar(prompt(List.of()));
        servidor.verify(postRequestedFor(urlEqualTo("/api/chat"))
            .withRequestBody(matchingJsonPath("$.messages[1]"))
            .withRequestBody(notMatching("(?s).*\"images\".*")));
    }

    @Test
    void devuelveContenido() {
        stubOk();
        String r = cliente(servidor.baseUrl(), Duration.ofSeconds(5), null).completar(prompt(List.of()));
        assertThat(r).isEqualTo("{\"cabecera\":{}}");
    }

    private static final String MSG_LIMITE =
        "La respuesta del modelo superó el límite de tokens (EXTRACTOR_OLLAMA_NUMPREDICT).";

    @Test
    void limiteDeTokensEs422() {
        servidor.stubFor(post("/api/chat").willReturn(okJson(
            "{\"message\":{\"content\":\"{\\\"cabecera\\\":{\"},\"done_reason\":\"length\",\"eval_count\":2048}")));
        var c = cliente(servidor.baseUrl(), Duration.ofSeconds(5), null);
        assertThatThrownBy(() -> c.completar(prompt(List.of())))
            .isInstanceOf(LlmLimiteTokensException.class).hasMessage(MSG_LIMITE);
    }

    @Test
    void limiteDeTokensConJsonValidoIgualEs422() {
        servidor.stubFor(post("/api/chat").willReturn(okJson(
            "{\"message\":{\"content\":\"{}\"},\"done_reason\":\"length\",\"eval_count\":2048}")));
        var c = cliente(servidor.baseUrl(), Duration.ofSeconds(5), null);
        assertThatThrownBy(() -> c.completar(prompt(List.of())))
            .isInstanceOf(LlmLimiteTokensException.class).hasMessage(MSG_LIMITE);
    }

    @Test
    void doneReasonStopDevuelveContenido() {
        servidor.stubFor(post("/api/chat").willReturn(okJson(
            "{\"message\":{\"content\":\"{}\"},\"done_reason\":\"stop\",\"eval_count\":12}")));
        String r = cliente(servidor.baseUrl(), Duration.ofSeconds(5), null).completar(prompt(List.of()));
        assertThat(r).isEqualTo("{}");
    }

    @Test
    void timeoutEs504() {
        servidor.stubFor(post("/api/chat").willReturn(okJson("{\"message\":{\"content\":\"{}\"}}")
            .withFixedDelay(2000)));
        var c = cliente(servidor.baseUrl(), Duration.ofMillis(500), null);
        assertThatThrownBy(() -> c.completar(prompt(List.of()))).isInstanceOf(LlmTimeoutException.class);
    }

    @Test
    void servidorCaidoEs503() throws Exception {
        int puerto;
        try (ServerSocket s = new ServerSocket(0)) {
            puerto = s.getLocalPort();
        }
        var c = cliente("http://localhost:" + puerto, Duration.ofSeconds(2), null);
        assertThatThrownBy(() -> c.completar(prompt(List.of()))).isInstanceOf(LlmNoDisponibleException.class);
    }

    @Test
    void modeloInexistenteEs503() {
        servidor.stubFor(post("/api/chat").willReturn(notFound()));
        var c = cliente(servidor.baseUrl(), Duration.ofSeconds(5), null);
        assertThatThrownBy(() -> c.completar(prompt(List.of()))).isInstanceOf(LlmNoDisponibleException.class);
    }

    @Test
    void error500Es503() {
        servidor.stubFor(post("/api/chat").willReturn(serverError()));
        var c = cliente(servidor.baseUrl(), Duration.ofSeconds(5), null);
        assertThatThrownBy(() -> c.completar(prompt(List.of()))).isInstanceOf(LlmNoDisponibleException.class);
    }

    @Test
    void cuerpoNoJsonEs503() {
        servidor.stubFor(post("/api/chat").willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "text/plain").withBody("no es json")));
        var c = cliente(servidor.baseUrl(), Duration.ofSeconds(5), null);
        assertThatThrownBy(() -> c.completar(prompt(List.of())))
            .isInstanceOf(LlmNoDisponibleException.class)
            .hasMessageNotContaining("no es json");
    }

    @Test
    void timeoutDeConexionEs503() {
        var props = new ExtractorProperties(
            new ExtractorProperties.Ollama("http://10.255.255.1:11434", "qwen3-vl:4b", Duration.ofSeconds(5), 8192, null, 2048),
            new ExtractorProperties.Concurrencia(1, Duration.ofSeconds(60)),
            new ExtractorProperties.Documento(10, 150, 200, 2000, 50),
            new ExtractorProperties.Debug(false));
        var c = new OllamaLlmCliente(props, Duration.ofMillis(300));
        assertThatThrownBy(() -> c.completar(prompt(List.of()))).isInstanceOf(LlmNoDisponibleException.class);
    }

    @Test
    void advierteSiElPromptLlenaElContexto(CapturedOutput salida) {
        servidor.stubFor(post("/api/chat").willReturn(okJson(
            "{\"message\":{\"content\":\"{}\"},\"eval_count\":12,\"prompt_eval_count\":8192}")));
        cliente(servidor.baseUrl(), Duration.ofSeconds(5), null).completar(prompt(List.of()));
        assertThat(salida.getAll()).contains("posible truncamiento de contexto").contains("prompt_eval_count=8192");
    }

    @Test
    void sinAdvertenciaSiElPromptCabeEnElContexto(CapturedOutput salida) {
        servidor.stubFor(post("/api/chat").willReturn(okJson(
            "{\"message\":{\"content\":\"{}\"},\"eval_count\":12,\"prompt_eval_count\":900}")));
        cliente(servidor.baseUrl(), Duration.ofSeconds(5), null).completar(prompt(List.of()));
        assertThat(salida.getAll()).doesNotContain("posible truncamiento").contains("prompt_eval_count=900");
    }
}
