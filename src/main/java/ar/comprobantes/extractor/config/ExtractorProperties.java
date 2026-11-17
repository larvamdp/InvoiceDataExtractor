package ar.comprobantes.extractor.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("extractor")
public record ExtractorProperties(
    @DefaultValue Ollama ollama,
    @DefaultValue Concurrencia concurrencia,
    @DefaultValue Documento documento,
    @DefaultValue Debug debug) {

    public record Ollama(
        @DefaultValue("http://localhost:11434") String url,
        @DefaultValue("qwen3-vl:4b-instruct") String modelo,
        @DefaultValue("90s") Duration timeout,
        @DefaultValue("8192") int numCtx,
        Boolean think,
        @DefaultValue("2048") int numPredict) {
    }

    public record Concurrencia(
        @DefaultValue("1") int maxLlm,
        @DefaultValue("60s") Duration esperaMaxima) {
    }

    public record Documento(
        @DefaultValue("10") int maxPaginas,
        @DefaultValue("150") int dpiImagen,
        @DefaultValue("200") int dpiQr,
        @DefaultValue("2000") int maxLadoPx,
        @DefaultValue("50") int umbralCaracteresPorPagina) {
    }

    public record Debug(@DefaultValue("false") boolean logPrompts) {
    }
}
