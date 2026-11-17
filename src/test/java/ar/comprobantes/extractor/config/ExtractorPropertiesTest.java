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
            assertThat(p.ollama().modelo()).isEqualTo("qwen3-vl:4b");
            assertThat(p.ollama().timeout()).isEqualTo(Duration.ofSeconds(90));
            assertThat(p.ollama().numCtx()).isEqualTo(8192);
            assertThat(p.ollama().think()).isNull();
            assertThat(p.ollama().numPredict()).isEqualTo(2048);
            assertThat(p.concurrencia().maxLlm()).isEqualTo(1);
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
}
