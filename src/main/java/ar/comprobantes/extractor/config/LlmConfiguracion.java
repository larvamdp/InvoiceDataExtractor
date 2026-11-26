package ar.comprobantes.extractor.config;

import ar.comprobantes.extractor.llm.LlmCliente;
import ar.comprobantes.extractor.llm.OllamaLlmCliente;
import ar.comprobantes.extractor.llm.nube.AzureOpenAiLlmCliente;
import ar.comprobantes.extractor.llm.nube.TokenCuentaServicio;
import ar.comprobantes.extractor.llm.nube.VertexGeminiLlmCliente;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Elige el cliente de IA según extractor.llm.proveedor y valida la configuración al arrancar. */
@Configuration
public class LlmConfiguracion {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguracion.class);
    private static final String API_VERSION_SIN_RAZONAMIENTO = "2024-10-21";

    @Bean
    public LlmCliente llmCliente(ExtractorProperties p) {
        List<String> faltantes = new ArrayList<>();
        switch (p.proveedor()) {
            case AZURE_OPENAI -> {
                ExtractorProperties.Azure a = p.azure();
                exigir(faltantes, a == null ? null : a.endpoint(), "EXTRACTOR_AZURE_ENDPOINT");
                exigir(faltantes, a == null ? null : a.deployment(), "EXTRACTOR_AZURE_DEPLOYMENT");
                exigir(faltantes, a == null ? null : a.apiKey(), "EXTRACTOR_AZURE_APIKEY");
            }
            case VERTEX_GEMINI -> {
                ExtractorProperties.Vertex v = p.vertex();
                exigir(faltantes, v == null ? null : v.proyecto(), "EXTRACTOR_VERTEX_PROYECTO");
                exigir(faltantes, v == null ? null : v.region(), "EXTRACTOR_VERTEX_REGION");
                exigir(faltantes, v == null ? null : v.modelo(), "EXTRACTOR_VERTEX_MODELO");
                if (v == null || v.credenciales() == null) {
                    faltantes.add("EXTRACTOR_VERTEX_CREDENCIALES");
                }
            }
            case OLLAMA -> { }
        }
        ExtractorProperties.Proxy proxy = p.proxy();
        if (proxy != null && proxy.activo()) {
            if (proxy.puerto() == null) {
                faltantes.add("EXTRACTOR_PROXY_PUERTO");
            }
            if (noVacio(proxy.usuario()) && !noVacio(proxy.clave())) {
                faltantes.add("EXTRACTOR_PROXY_CLAVE");
            }
        }
        if (!faltantes.isEmpty()) {
            throw new IllegalStateException(
                "Configuración de IA incompleta; faltan las variables: " + String.join(", ", faltantes));
        }

        String proxyTexto = proxy != null && proxy.activo() ? proxy.host() + ":" + proxy.puerto() : "no";
        return switch (p.proveedor()) {
            case AZURE_OPENAI -> {
                ExtractorProperties.Azure a = p.azure();
                if (a.razonamiento() && API_VERSION_SIN_RAZONAMIENTO.equals(a.apiVersion())) {
                    log.warn("EXTRACTOR_AZURE_RAZONAMIENTO=true con api-version 2024-10-21: esa versión no acepta "
                        + "reasoning_effort; usar 2024-12-01-preview o posterior (EXTRACTOR_AZURE_APIVERSION).");
                }
                log.info("Proveedor de IA: proveedor={}, modelo={}, destino={}, proxy={}",
                    p.proveedor(), a.deployment(), a.endpoint(), proxyTexto);
                yield new AzureOpenAiLlmCliente(p);
            }
            case VERTEX_GEMINI -> {
                ExtractorProperties.Vertex v = p.vertex();
                TokenCuentaServicio token = new TokenCuentaServicio(v.credenciales(), proxy);
                if ("global".equalsIgnoreCase(v.region())) {
                    log.warn("Vertex en ubicación global: Google puede procesar los datos en cualquier región.");
                }
                log.info("Proveedor de IA: proveedor={}, modelo={}, destino={}, proxy={}",
                    p.proveedor(), v.modelo(), v.region(), proxyTexto);
                yield new VertexGeminiLlmCliente(p, token);
            }
            case OLLAMA -> {
                log.info("Proveedor de IA: proveedor={}, modelo={}, destino={}, proxy={}",
                    p.proveedor(), p.ollama().modelo(), p.ollama().url(), proxyTexto);
                yield new OllamaLlmCliente(p);
            }
        };
    }

    private static void exigir(List<String> faltantes, String valor, String variable) {
        if (!noVacio(valor)) {
            faltantes.add(variable);
        }
    }

    private static boolean noVacio(String valor) {
        return valor != null && !valor.isBlank();
    }
}
