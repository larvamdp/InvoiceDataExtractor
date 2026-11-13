package ar.comprobantes.extractor.api;

import static org.assertj.core.api.Assertions.assertThat;

import ar.comprobantes.extractor.llm.LlmCliente;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Servidor real: aplica el límite multipart del servlet (R3). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArchivoGrandeTest {

    @LocalServerPort
    int puerto;

    @MockitoBean
    LlmCliente llm;

    @Test
    void archivoMayorA10MbEs413() throws Exception {
        String borde = "----limite";
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        cuerpo.write(("--" + borde + "\r\nContent-Disposition: form-data; name=\"documento\"; filename=\"g.pdf\"\r\n"
            + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        cuerpo.write(new byte[10 * 1024 * 1024 + 1024]);
        cuerpo.write(("\r\n--" + borde + "\r\nContent-Disposition: form-data; name=\"solicitud\"\r\n"
            + "Content-Type: application/json\r\n\r\n{\"camposCabecera\":[{\"nombre\":\"a\"}]}\r\n--" + borde
            + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + "/api/v1/extracciones/sync"))
            .header("Content-Type", "multipart/form-data; boundary=" + borde)
            .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray())).build();
        HttpResponse<String> resp = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            .send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(413);
        assertThat(resp.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
    }
}
