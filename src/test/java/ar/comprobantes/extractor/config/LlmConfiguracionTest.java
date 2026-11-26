package ar.comprobantes.extractor.config;

import static org.assertj.core.api.Assertions.assertThat;

import ar.comprobantes.extractor.llm.LlmCliente;
import ar.comprobantes.extractor.llm.OllamaLlmCliente;
import ar.comprobantes.extractor.llm.nube.AzureOpenAiLlmCliente;
import ar.comprobantes.extractor.llm.nube.CuentaServicioDePrueba;
import ar.comprobantes.extractor.llm.nube.VertexGeminiLlmCliente;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LlmConfiguracionTest {

    @TempDir
    Path tmp;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(LlmConfiguracion.class, Habilitar.class);

    @EnableConfigurationProperties(ExtractorProperties.class)
    static class Habilitar {
    }

    private static final String[] AZURE = {
        "extractor.llm.proveedor=AZURE_OPENAI",
        "extractor.azure.endpoint=https://recurso.openai.azure.com",
        "extractor.azure.deployment=gpt-4o",
        "extractor.azure.api-key=SECRETO-KEY-123"};

    @Test
    void porDefectoOllama() {
        runner.run(ctx -> assertThat(ctx.getBean(LlmCliente.class)).isInstanceOf(OllamaLlmCliente.class));
    }

    @Test
    void azureCompleto(CapturedOutput salida) {
        runner.withPropertyValues(AZURE).run(ctx -> {
            assertThat(ctx.getBean(LlmCliente.class)).isInstanceOf(AzureOpenAiLlmCliente.class);
            assertThat(salida).contains("Proveedor de IA: proveedor=AZURE_OPENAI")
                .contains("https://recurso.openai.azure.com").doesNotContain("SECRETO-KEY-123");
        });
    }

    @Test
    void azureIncompletoNoArranca() {
        runner.withPropertyValues("extractor.llm.proveedor=AZURE_OPENAI").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("EXTRACTOR_AZURE_ENDPOINT")
                .hasStackTraceContaining("EXTRACTOR_AZURE_DEPLOYMENT")
                .hasStackTraceContaining("EXTRACTOR_AZURE_APIKEY");
        });
    }

    @Test
    void azureRazonamientoConApiVersionViejaAdvierte(CapturedOutput salida) {
        runner.withPropertyValues(AZURE).withPropertyValues("extractor.azure.razonamiento=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(salida).contains("EXTRACTOR_AZURE_RAZONAMIENTO=true con api-version 2024-10-21");
        });
    }

    @Test
    void azureRazonamientoConApiVersionNuevaNoAdvierte(CapturedOutput salida) {
        runner.withPropertyValues(AZURE).withPropertyValues("extractor.azure.razonamiento=true",
            "extractor.azure.api-version=2024-12-01-preview").run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(salida).doesNotContain("esa versión no acepta reasoning_effort");
            });
    }

    private String[] vertex(String... extra) throws Exception {
        Path cred = CuentaServicioDePrueba.escribir(tmp, "https://oauth2.googleapis.com/token");
        String[] base = {
            "extractor.llm.proveedor=VERTEX_GEMINI",
            "extractor.vertex.proyecto=proyecto-prueba",
            "extractor.vertex.region=us-central1",
            "extractor.vertex.modelo=gemini-2.5-flash",
            "extractor.vertex.credenciales=" + cred};
        String[] todo = new String[base.length + extra.length];
        System.arraycopy(base, 0, todo, 0, base.length);
        System.arraycopy(extra, 0, todo, base.length, extra.length);
        return todo;
    }

    @Test
    void vertexCompleto(CapturedOutput salida) throws Exception {
        runner.withPropertyValues(vertex()).run(ctx -> {
            assertThat(ctx.getBean(LlmCliente.class)).isInstanceOf(VertexGeminiLlmCliente.class);
            assertThat(salida).contains("Proveedor de IA: proveedor=VERTEX_GEMINI")
                .doesNotContain("ubicación global");
        });
    }

    @Test
    void vertexGlobalAdvierte(CapturedOutput salida) throws Exception {
        runner.withPropertyValues(vertex("extractor.vertex.region=global")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(salida).contains("Vertex en ubicación global");
        });
    }

    @Test
    void vertexSinCredencialesNoArranca() {
        runner.withPropertyValues("extractor.llm.proveedor=VERTEX_GEMINI",
            "extractor.vertex.proyecto=p", "extractor.vertex.region=us-central1",
            "extractor.vertex.modelo=gemini-2.5-flash").run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("EXTRACTOR_VERTEX_CREDENCIALES");
            });
    }

    @Test
    void proxySinPuertoNoArranca() {
        runner.withPropertyValues("extractor.proxy.host=proxy.local").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("EXTRACTOR_PROXY_PUERTO");
        });
    }

    @Test
    void proxyConUsuarioSinClaveNoArranca() {
        runner.withPropertyValues("extractor.proxy.host=proxy.local", "extractor.proxy.puerto=3128",
            "extractor.proxy.usuario=u").run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("EXTRACTOR_PROXY_CLAVE");
            });
    }

    @Test
    void proxyConUsuarioNoFiltraLaClave(CapturedOutput salida) {
        runner.withPropertyValues(AZURE).withPropertyValues("extractor.proxy.host=proxy.local",
            "extractor.proxy.puerto=3128", "extractor.proxy.usuario=u", "extractor.proxy.clave=CLAVE-PROXY-XYZ")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(salida).contains("proxy=proxy.local:3128").doesNotContain("CLAVE-PROXY-XYZ");
            });
    }
}
