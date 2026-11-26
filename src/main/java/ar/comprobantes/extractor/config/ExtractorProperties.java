package ar.comprobantes.extractor.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("extractor")
public record ExtractorProperties(
    @DefaultValue Llm llm,
    @DefaultValue Ollama ollama,
    @DefaultValue Azure azure,
    @DefaultValue Vertex vertex,
    @DefaultValue Proxy proxy,
    @DefaultValue Concurrencia concurrencia,
    @DefaultValue Documento documento,
    @DefaultValue Debug debug) {

    @ConstructorBinding
    public ExtractorProperties {
    }

    /** Constructor de compatibilidad: sin bloques de nube ni proxy (Ollama por defecto). */
    public ExtractorProperties(Ollama ollama, Concurrencia concurrencia, Documento documento, Debug debug) {
        this(null, ollama, null, null, null, concurrencia, documento, debug);
    }

    public ProveedorLlm proveedor() {
        return llm == null || llm.proveedor() == null ? ProveedorLlm.OLLAMA : llm.proveedor();
    }

    /** Concurrencia configurada, o por defecto 1 con Ollama y 4 con proveedores en la nube. */
    public int maxLlmEfectivo() {
        Integer configurado = concurrencia == null ? null : concurrencia.maxLlm();
        if (configurado != null) {
            return configurado;
        }
        return proveedor() == ProveedorLlm.OLLAMA ? 1 : 4;
    }

    public record Llm(@DefaultValue("OLLAMA") ProveedorLlm proveedor) {
    }

    public record Azure(
        String endpoint,
        String deployment,
        String apiKey,
        @DefaultValue("2024-10-21") String apiVersion,
        @DefaultValue("90s") Duration timeout,
        @DefaultValue("4096") int maxTokens,
        @DefaultValue("false") boolean razonamiento) {

        @Override
        public String toString() {
            return "Azure[endpoint=" + endpoint + ", deployment=" + deployment + ", apiKey=****, apiVersion="
                + apiVersion + ", timeout=" + timeout + ", maxTokens=" + maxTokens
                + ", razonamiento=" + razonamiento + "]";
        }
    }

    public record Vertex(
        String proyecto,
        String region,
        String modelo,
        Path credenciales,
        @DefaultValue("90s") Duration timeout,
        @DefaultValue("4096") int maxTokens,
        Integer presupuestoRazonamiento) {
    }

    public record Proxy(String host, Integer puerto, String usuario, String clave, Path caExtra) {

        public boolean activo() {
            return host != null && !host.isBlank();
        }

        @Override
        public String toString() {
            return "Proxy[host=" + host + ", puerto=" + puerto + ", usuario=" + usuario
                + ", clave=****, caExtra=" + caExtra + "]";
        }
    }

    public record Ollama(
        @DefaultValue("http://localhost:11434") String url,
        @DefaultValue("qwen3-vl:4b-instruct") String modelo,
        @DefaultValue("90s") Duration timeout,
        @DefaultValue("8192") int numCtx,
        Boolean think,
        @DefaultValue("2048") int numPredict) {
    }

    public record Concurrencia(
        Integer maxLlm,
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
