package ar.comprobantes.extractor.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ar.comprobantes.extractor.llm.LlmCliente;
import ar.comprobantes.extractor.llm.PromptLlm;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Confidencialidad: el servidor real no debe escribir ninguna parte multipart (upload_*.tmp) a disco. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SinArchivosTemporalesTest {

    private static final Path BASE = crear();
    private static final Path UPLOADS = BASE.resolve("uploads");

    private static Path crear() {
        try {
            Path base = Files.createTempDirectory("extractor-tomcat");
            Files.createDirectories(base.resolve("uploads"));
            return base;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("server.tomcat.basedir", BASE::toString);
        registry.add("spring.servlet.multipart.location", UPLOADS::toString);
    }

    @LocalServerPort
    int puerto;

    @MockitoBean
    LlmCliente llm;

    @Test
    void unaExtraccionNoCreaArchivosDeCargaEnDisco() throws Exception {
        when(llm.modelo()).thenReturn("falso");
        when(llm.completar(any(PromptLlm.class))).thenReturn("{\"cabecera\":{},\"detalle\":[]}");
        byte[] pdf = PdfsDePrueba.pdf("Factura 0003-00012345 de Razon Social de ejemplo sociedad anonima");
        String borde = "----sinDisco";
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        cuerpo.write(("--" + borde + "\r\nContent-Disposition: form-data; name=\"documento\"; filename=\"f.pdf\"\r\n"
            + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        cuerpo.write(pdf);
        cuerpo.write(("\r\n--" + borde + "\r\nContent-Disposition: form-data; name=\"solicitud\"\r\n"
            + "Content-Type: application/json\r\n\r\n{\"camposCabecera\":[{\"nombre\":\"a\"}]}\r\n--" + borde
            + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + "/api/v1/extracciones/sync"))
            .header("Content-Type", "multipart/form-data; boundary=" + borde)
            .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray())).build();

        List<String> creados = new ArrayList<>();
        try (WatchService watcher = BASE.getFileSystem().newWatchService()) {
            UPLOADS.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            HttpResponse<String> resp = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            WatchKey clave;
            while ((clave = watcher.poll(300, TimeUnit.MILLISECONDS)) != null) {
                for (WatchEvent<?> e : clave.pollEvents()) {
                    creados.add(String.valueOf(e.context()));
                }
                clave.reset();
            }
        }
        assertThat(creados).as("archivos creados en la ubicación multipart").isEmpty();
        try (var restos = Files.list(UPLOADS)) {
            assertThat(restos.toList()).isEmpty();
        }
    }
}
