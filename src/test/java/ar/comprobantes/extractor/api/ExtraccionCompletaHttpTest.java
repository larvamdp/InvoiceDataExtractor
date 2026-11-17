package ar.comprobantes.extractor.api;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Pila completa por HTTP: controlador, procesamiento, cliente Ollama real contra WireMock, normalización. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExtraccionCompletaHttpTest {

    private static final WireMockServer OLLAMA = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        OLLAMA.start();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("extractor.ollama.url", OLLAMA::baseUrl);
        registry.add("extractor.ollama.modelo", () -> "modelo-wiremock");
    }

    @AfterAll
    static void detener() {
        OLLAMA.stop();
    }

    @LocalServerPort
    int puerto;

    @Test
    void extraccionPorHttpConOllamaSimulado() throws Exception {
        String contenido = "{\"cabecera\":{\"importeTotal\":\"15.000,00\"},\"detalle\":[]}";
        String respuesta = new ObjectMapper().writeValueAsString(
            Map.of("message", Map.of("role", "assistant", "content", contenido),
                "eval_count", 10, "prompt_eval_count", 100));
        OLLAMA.stubFor(post("/api/chat").willReturn(okJson(respuesta)));

        byte[] pdf = PdfsDePrueba.pdf("Factura 0003-00012345 de Razon Social de ejemplo sociedad anonima",
            "TOTAL $ 15.000,00");
        String borde = "----completo";
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        cuerpo.write(("--" + borde + "\r\nContent-Disposition: form-data; name=\"documento\"; filename=\"f.pdf\"\r\n"
            + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        cuerpo.write(pdf);
        cuerpo.write(("\r\n--" + borde + "\r\nContent-Disposition: form-data; name=\"solicitud\"\r\n"
            + "Content-Type: application/json\r\n\r\n"
            + "{\"camposCabecera\":[{\"nombre\":\"importeTotal\",\"tipo\":\"IMPORTE\"}]}\r\n--" + borde
            + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + "/api/v1/extracciones/sync"))
            .header("Content-Type", "multipart/form-data; boundary=" + borde)
            .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray())).build();

        HttpResponse<String> resp = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            .send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode json = new ObjectMapper().readTree(resp.body());
        assertThat(json.path("cabecera").path("importeTotal").path("valor").decimalValue())
            .isEqualByComparingTo("15000.00");
        assertThat(json.path("cabecera").path("importeTotal").path("fuente").asText()).isEqualTo("DOCUMENTO");
        assertThat(json.path("metadatos").path("modelo").asText()).isEqualTo("modelo-wiremock");
        OLLAMA.verify(postRequestedFor(urlEqualTo("/api/chat"))
            .withRequestBody(matchingJsonPath("$.format.type", equalTo("object")))
            .withRequestBody(matchingJsonPath("$.model", equalTo("modelo-wiremock"))));
    }
}
