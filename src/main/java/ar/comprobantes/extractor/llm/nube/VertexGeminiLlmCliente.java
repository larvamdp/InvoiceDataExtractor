package ar.comprobantes.extractor.llm.nube;

import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.LlmLimiteTokensException;
import ar.comprobantes.extractor.error.RespuestaLlmInvalidaException;
import ar.comprobantes.extractor.llm.LlmCliente;
import ar.comprobantes.extractor.llm.PromptLlm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Cliente de Vertex AI Gemini (generateContent con responseSchema). El bean lo registra la configuración. */
public class VertexGeminiLlmCliente implements LlmCliente {

    private static final Logger log = LoggerFactory.getLogger(VertexGeminiLlmCliente.class);
    private static final String PROVEEDOR = "VERTEX_GEMINI";
    private static final String MSG_LIMITE_TOKENS =
        "La respuesta del modelo superó el límite de tokens (EXTRACTOR_VERTEX_MAXTOKENS).";
    private static final String MSG_INVALIDA = "El proveedor de IA no devolvió una respuesta utilizable.";
    private static final Set<String> FIN_BLOQUEADO =
        Set.of("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII");

    private final ExtractorProperties.Vertex config;
    private final ProveedorToken token;
    private final boolean hayProxy;
    private final boolean logPrompts;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient restClient;

    public VertexGeminiLlmCliente(ExtractorProperties props, ProveedorToken token) {
        this(props, token, urlBase(props.vertex().region()));
    }

    VertexGeminiLlmCliente(ExtractorProperties props, ProveedorToken token, String baseUrl) {
        this.config = props.vertex();
        this.token = token;
        this.logPrompts = props.debug().logPrompts();
        ExtractorProperties.Proxy proxy = props.proxy();
        this.hayProxy = proxy != null && proxy.activo();
        this.restClient = HttpNube.restClient(baseUrl, config.timeout(), proxy);
    }

    static String urlBase(String region) {
        return "global".equalsIgnoreCase(region)
            ? "https://aiplatform.googleapis.com"
            : "https://" + region + "-aiplatform.googleapis.com";
    }

    @Override
    public String modelo() {
        return config.modelo();
    }

    @Override
    public String completar(PromptLlm prompt) {
        int cantImagenes = prompt.imagenesBase64() == null ? 0 : prompt.imagenesBase64().size();
        ObjectNode cuerpo = armarCuerpo(prompt);
        if (logPrompts) {
            log.debug("ADVERTENCIA: extractor.debug.log-prompts activo, se registra contenido del documento. "
                + "Prompt: sistema={} usuario={} [{} imagenes]", prompt.sistema(), prompt.usuario(), cantImagenes);
        }
        // Si el token falla lanza LlmNoDisponibleException y se propaga tal cual.
        String bearer = token.token();
        long inicio = System.nanoTime();
        JsonNode respuesta;
        try {
            respuesta = restClient.post()
                .uri("/v1/projects/{proyecto}/locations/{region}/publishers/google/models/{modelo}:generateContent",
                    config.proyecto(), config.region(), config.modelo())
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientException e) {
            throw ErroresNube.traducir(e, PROVEEDOR, config.modelo(), hayProxy);
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        if (respuesta == null) {
            log.warn("Respuesta de Vertex Gemini vacía (modelo={})", config.modelo());
            throw new RespuestaLlmInvalidaException(MSG_INVALIDA);
        }
        JsonNode uso = respuesta.path("usageMetadata");
        log.info("Vertex Gemini completó: modelo={}, imagenes={}, duracionMs={}, promptTokenCount={}, "
                + "candidatesTokenCount={}, thoughtsTokenCount={}",
            config.modelo(), cantImagenes, ms, uso.path("promptTokenCount").asText("n/d"),
            uso.path("candidatesTokenCount").asText("n/d"), uso.path("thoughtsTokenCount").asText("n/d"));
        JsonNode bloqueo = respuesta.path("promptFeedback").path("blockReason");
        JsonNode candidato = respuesta.path("candidates").path(0);
        if (!bloqueo.isMissingNode() && !bloqueo.isNull() || candidato.isMissingNode()) {
            log.warn("Respuesta de Vertex Gemini sin candidatos o bloqueada: modelo={}, blockReason={}",
                config.modelo(), bloqueo.asText("n/d"));
            throw new RespuestaLlmInvalidaException(MSG_INVALIDA);
        }
        String fin = candidato.path("finishReason").asText("");
        if ("MAX_TOKENS".equals(fin)) {
            log.warn("El modelo alcanzó el límite de tokens: modelo={}, maxOutputTokens={}",
                config.modelo(), config.maxTokens());
            throw new LlmLimiteTokensException(MSG_LIMITE_TOKENS);
        }
        StringBuilder texto = new StringBuilder();
        boolean hayTexto = false;
        for (JsonNode parte : candidato.path("content").path("parts")) {
            if (parte.path("thought").asBoolean(false) || !parte.path("text").isTextual()) {
                continue;
            }
            texto.append(parte.path("text").asText());
            hayTexto = true;
        }
        if (FIN_BLOQUEADO.contains(fin) || !hayTexto) {
            log.warn("Respuesta de Vertex Gemini no utilizable: modelo={}, finishReason={}", config.modelo(), fin);
            throw new RespuestaLlmInvalidaException(MSG_INVALIDA);
        }
        if (logPrompts) {
            log.debug("ADVERTENCIA: extractor.debug.log-prompts activo. Respuesta: {}", texto);
        }
        return texto.toString();
    }

    private ObjectNode armarCuerpo(PromptLlm prompt) {
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.putObject("systemInstruction").putArray("parts").addObject().put("text", prompt.sistema());
        ObjectNode usuario = cuerpo.putArray("contents").addObject();
        usuario.put("role", "user");
        ArrayNode partes = usuario.putArray("parts");
        partes.addObject().put("text", prompt.usuario());
        if (prompt.imagenesBase64() != null) {
            for (String img : prompt.imagenesBase64()) {
                ObjectNode datos = partes.addObject().putObject("inlineData");
                datos.put("mimeType", "image/png");
                datos.put("data", img);
            }
        }
        ObjectNode gen = cuerpo.putObject("generationConfig");
        gen.put("temperature", 0);
        gen.put("maxOutputTokens", config.maxTokens());
        gen.put("responseMimeType", "application/json");
        gen.set("responseSchema", EsquemaGemini.adaptar(prompt.esquema()));
        if (config.presupuestoRazonamiento() != null) {
            gen.putObject("thinkingConfig").put("thinkingBudget", config.presupuestoRazonamiento());
        }
        return cuerpo;
    }
}
