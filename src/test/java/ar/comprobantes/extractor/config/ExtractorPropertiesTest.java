package ar.comprobantes.extractor.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class ExtractorPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(ExtractorProperties.class)
    static class Config {
    }

    @Test
    void defaults() {
        runner.run(ctx -> {
            var p = ctx.getBean(ExtractorProperties.class);
            assertThat(p.ollama().url()).isEqualTo("http://localhost:11434");
            assertThat(p.ollama().modelo()).isEqualTo("qwen3-vl:4b-instruct");
            assertThat(p.ollama().timeout()).isEqualTo(Duration.ofSeconds(90));
            assertThat(p.ollama().numCtx()).isEqualTo(8192);
            assertThat(p.ollama().think()).isNull();
            assertThat(p.ollama().numPredict()).isEqualTo(2048);
            assertThat(p.proveedor()).isEqualTo(ProveedorLlm.OLLAMA);
            assertThat(p.concurrencia().maxLlm()).isNull();
            assertThat(p.maxLlmEfectivo()).isEqualTo(1);
            assertThat(p.azure().apiVersion()).isEqualTo("2024-10-21");
            assertThat(p.azure().maxTokens()).isEqualTo(4096);
            assertThat(p.azure().razonamiento()).isFalse();
            assertThat(p.vertex().maxTokens()).isEqualTo(4096);
            assertThat(p.vertex().timeout()).isEqualTo(Duration.ofSeconds(90));
            assertThat(p.proxy().activo()).isFalse();
            assertThat(p.concurrencia().esperaMaxima()).isEqualTo(Duration.ofSeconds(60));
            assertThat(p.documento().maxPaginas()).isEqualTo(10);
            assertThat(p.documento().dpiImagen()).isEqualTo(150);
            assertThat(p.documento().dpiQr()).isEqualTo(200);
            assertThat(p.documento().maxLadoPx()).isEqualTo(2000);
            assertThat(p.documento().umbralCaracteresPorPagina()).isEqualTo(50);
            assertThat(p.debug().logPrompts()).isFalse();
        });
    }

    @Test
    void sobreescribeConPropiedades() {
        runner.withPropertyValues("extractor.ollama.modelo=qwen3-vl:8b", "extractor.ollama.think=false")
            .run(ctx -> {
                var p = ctx.getBean(ExtractorProperties.class);
                assertThat(p.ollama().modelo()).isEqualTo("qwen3-vl:8b");
                assertThat(p.ollama().think()).isFalse();
            });
    }

    @Test
    void variableDeEntornoSobreescribeModelo() {
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment",
                    Map.of("EXTRACTOR_OLLAMA_MODELO", "qwen3-vl:8b"))))
            .run(ctx -> assertThat(ctx.getBean(ExtractorProperties.class).ollama().modelo())
                .isEqualTo("qwen3-vl:8b"));
    }

    @Test
    void nubeTieneConcurrencia4() {
        runner.withPropertyValues("extractor.llm.proveedor=VERTEX_GEMINI")
            .run(ctx -> assertThat(ctx.getBean(ExtractorProperties.class).maxLlmEfectivo()).isEqualTo(4));
    }

    @Test
    void concurrenciaExplicitaManda() {
        runner.withPropertyValues("extractor.llm.proveedor=AZURE_OPENAI", "extractor.concurrencia.max-llm=2")
            .run(ctx -> assertThat(ctx.getBean(ExtractorProperties.class).maxLlmEfectivo()).isEqualTo(2));
    }

    @Test
    void variablesDeEntorno() {
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                    "EXTRACTOR_LLM_PROVEEDOR", "AZURE_OPENAI",
                    "EXTRACTOR_AZURE_APIKEY", "k",
                    "EXTRACTOR_VERTEX_PRESUPUESTORAZONAMIENTO", "0",
                    "EXTRACTOR_PROXY_CAEXTRA", "/x.pem"))))
            .run(ctx -> {
                var p = ctx.getBean(ExtractorProperties.class);
                assertThat(p.proveedor()).isEqualTo(ProveedorLlm.AZURE_OPENAI);
                assertThat(p.azure().apiKey()).isEqualTo("k");
                assertThat(p.vertex().presupuestoRazonamiento()).isEqualTo(0);
                assertThat(p.proxy().caExtra()).isEqualTo(java.nio.file.Path.of("/x.pem"));
            });
    }

    @Test
    void variablesVaciasReenviadasPorComposeSeComportanComoSinDefinir() {
        Map<String, Object> vacias = new java.util.LinkedHashMap<>();
        for (String v : new String[] {"EXTRACTOR_LLM_PROVEEDOR", "EXTRACTOR_CONCURRENCIA_MAXLLM",
            "EXTRACTOR_AZURE_ENDPOINT", "EXTRACTOR_AZURE_DEPLOYMENT", "EXTRACTOR_AZURE_APIKEY",
            "EXTRACTOR_AZURE_APIVERSION", "EXTRACTOR_AZURE_TIMEOUT", "EXTRACTOR_AZURE_MAXTOKENS",
            "EXTRACTOR_AZURE_RAZONAMIENTO", "EXTRACTOR_VERTEX_PROYECTO", "EXTRACTOR_VERTEX_REGION",
            "EXTRACTOR_VERTEX_MODELO", "EXTRACTOR_VERTEX_CREDENCIALES", "EXTRACTOR_VERTEX_TIMEOUT",
            "EXTRACTOR_VERTEX_MAXTOKENS", "EXTRACTOR_VERTEX_PRESUPUESTORAZONAMIENTO",
            "EXTRACTOR_PROXY_HOST", "EXTRACTOR_PROXY_PUERTO", "EXTRACTOR_PROXY_USUARIO",
            "EXTRACTOR_PROXY_CLAVE", "EXTRACTOR_PROXY_CAEXTRA"}) {
            vacias.put(v, "");
        }
        // Primitivos (int/boolean) rompen el binding con valor vacío y un String vacío pisa su default:
        // compose reenvía estas con default explícito (no vacío).
        for (String v : new String[] {"EXTRACTOR_AZURE_MAXTOKENS", "EXTRACTOR_AZURE_RAZONAMIENTO",
            "EXTRACTOR_VERTEX_MAXTOKENS", "EXTRACTOR_AZURE_APIVERSION"}) {
            vacias.remove(v);
        }
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment", vacias)))
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                var p = ctx.getBean(ExtractorProperties.class);
                assertThat(p.proveedor()).isEqualTo(ProveedorLlm.OLLAMA);
                assertThat(p.maxLlmEfectivo()).isEqualTo(1);
                assertThat(p.azure().timeout()).isEqualTo(Duration.ofSeconds(90));
                assertThat(p.vertex().timeout()).isEqualTo(Duration.ofSeconds(90));
                assertThat(p.vertex().presupuestoRazonamiento()).isNull();
                assertThat(p.vertex().credenciales()).isNull();
                assertThat(p.proxy().activo()).isFalse();
                assertThat(p.proxy().puerto()).isNull();
                assertThat(p.proxy().caExtra()).isNull();
            });
    }

    @Test
    void toStringNoMuestraSecretos() {
        var azure = new ExtractorProperties.Azure("https://e", "d", "secreta", "v", Duration.ofSeconds(1), 1, false);
        var proxy = new ExtractorProperties.Proxy("h", 8080, "u", "clavesecreta", null);
        assertThat(azure.toString()).doesNotContain("secreta").contains("****");
        assertThat(proxy.toString()).doesNotContain("clavesecreta").contains("****");
    }
}
