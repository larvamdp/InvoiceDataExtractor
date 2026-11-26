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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Cliente de Azure OpenAI (chat completions con salida estructurada estricta). El bean lo registra la configuración. */
public class AzureOpenAiLlmCliente implements LlmCliente {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiLlmCliente.class);
    private static final String PROVEEDOR = "AZURE_OPENAI";
    private static final String MSG_LIMITE_TOKENS =
        "La respuesta del modelo superó el límite de tokens (EXTRACTOR_AZURE_MAXTOKENS).";
    private static final String MSG_INVALIDA = "El proveedor de IA no devolvió una respuesta utilizable.";

    private final ExtractorProperties.Azure config;
    private final boolean hayProxy;
    private final boolean logPrompts;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient restClient;

    public AzureOpenAiLlmCliente(ExtractorProperties props) {
        this.config = props.azure();
        this.logPrompts = props.debug().logPrompts();
        ExtractorProperties.Proxy proxy = props.proxy();
        this.hayProxy = proxy != null && proxy.activo();
        this.restClient = HttpNube.restClient(config.endpoint(), config.timeout(), proxy);
    }

    @Override
    public String modelo() {
        return config.deployment();
    }

    @Override
    public String completar(PromptLlm prompt) {
        int cantImagenes = prompt.imagenesBase64() == null ? 0 : prompt.imagenesBase64().size();
        ObjectNode cuerpo = armarCuerpo(prompt);
        if (logPrompts) {
            log.debug("ADVERTENCIA: extractor.debug.log-prompts activo, se registra contenido del documento. "
                + "Prompt: sistema={} usuario={} [{} imagenes]", prompt.sistema(), prompt.usuario(), cantImagenes);
        }
        long inicio = System.nanoTime();
        JsonNode respuesta;
        try {
            respuesta = restClient.post()
                .uri("/openai/deployments/{deployment}/chat/completions?api-version={v}",
                    config.deployment(), config.apiVersion())
                .header("api-key", config.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientException e) {
            throw ErroresNube.traducir(e, PROVEEDOR, config.deployment(), hayProxy);
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        JsonNode eleccion = respuesta == null ? null : respuesta.path("choices").path(0);
        if (eleccion == null || eleccion.isMissingNode()) {
            log.warn("Respuesta de Azure OpenAI sin choices (deployment={})", config.deployment());
            throw new RespuestaLlmInvalidaException(MSG_INVALIDA);
        }
        JsonNode usage = respuesta.path("usage");
        log.info("Azure OpenAI completó: deployment={}, imagenes={}, duracionMs={}, prompt_tokens={}, completion_tokens={}",
            config.deployment(), cantImagenes, ms, usage.path("prompt_tokens").asText("n/d"),
            usage.path("completion_tokens").asText("n/d"));
        String fin = eleccion.path("finish_reason").asText("");
        if ("length".equals(fin)) {
            log.warn("El modelo alcanzó el límite de tokens: deployment={}, max_completion_tokens={}",
                config.deployment(), config.maxTokens());
            throw new LlmLimiteTokensException(MSG_LIMITE_TOKENS);
        }
        JsonNode mensaje = eleccion.path("message");
        JsonNode contenido = mensaje.path("content");
        if ("content_filter".equals(fin) || !mensaje.path("refusal").isMissingNode() && !mensaje.path("refusal").isNull()
            || !contenido.isTextual()) {
            log.warn("Respuesta de Azure OpenAI no utilizable: deployment={}, finish_reason={}", config.deployment(), fin);
            throw new RespuestaLlmInvalidaException(MSG_INVALIDA);
        }
        if (logPrompts) {
            log.debug("ADVERTENCIA: extractor.debug.log-prompts activo. Respuesta: {}", contenido.asText());
        }
        return contenido.asText();
    }

    private ObjectNode armarCuerpo(PromptLlm prompt) {
        ObjectNode cuerpo = mapper.createObjectNode();
        ArrayNode mensajes = cuerpo.putArray("messages");
        mensajes.addObject().put("role", "system").put("content", prompt.sistema());
        ObjectNode usuario = mensajes.addObject();
        usuario.put("role", "user");
        ArrayNode partes = usuario.putArray("content");
        partes.addObject().put("type", "text").put("text", prompt.usuario());
        if (prompt.imagenesBase64() != null) {
            for (String img : prompt.imagenesBase64()) {
                ObjectNode parte = partes.addObject();
                parte.put("type", "image_url");
                parte.putObject("image_url").put("url", "data:image/png;base64," + img);
            }
        }
        ObjectNode formato = cuerpo.putObject("response_format");
        formato.put("type", "json_schema");
        ObjectNode js = formato.putObject("json_schema");
        js.put("name", "extraccion");
        js.put("strict", true);
        js.set("schema", EsquemaAzure.adaptar(prompt.esquema()));
        cuerpo.put("max_completion_tokens", config.maxTokens());
        if (config.razonamiento()) {
            cuerpo.put("reasoning_effort", "low");
        } else {
            cuerpo.put("temperature", 0);
        }
        return cuerpo;
    }
}
