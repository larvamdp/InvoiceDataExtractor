package ar.comprobantes.extractor.llm;

import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.LlmLimiteTokensException;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import ar.comprobantes.extractor.error.LlmTimeoutException;
import ar.comprobantes.extractor.llm.nube.ErroresNube;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public class OllamaLlmCliente implements LlmCliente {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmCliente.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String MSG_NO_DISPONIBLE = "El servicio de IA no está disponible.";
    private static final String MSG_TIMEOUT = "El modelo excedió el tiempo máximo de respuesta.";
    private static final String MSG_LIMITE_TOKENS =
        "La respuesta del modelo superó el límite de tokens (EXTRACTOR_OLLAMA_NUMPREDICT).";

    private final ExtractorProperties.Ollama config;
    private final boolean logPrompts;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient restClient;

    @Autowired
    public OllamaLlmCliente(ExtractorProperties props) {
        this(props, CONNECT_TIMEOUT);
    }

    OllamaLlmCliente(ExtractorProperties props, Duration connectTimeout) {
        this.config = props.ollama();
        this.logPrompts = props.debug().logPrompts();
        // HTTP/1.1 forzado: el upgrade h2c por defecto del cliente JDK falla contra servidores HTTP/1.1 (WireMock/Jetty).
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(config.timeout());
        this.restClient = RestClient.builder().baseUrl(config.url()).requestFactory(factory).build();
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
        long inicio = System.nanoTime();
        JsonNode respuesta;
        try {
            respuesta = restClient.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            log.warn("Ollama respondió HTTP {} (modelo={})", e.getStatusCode().value(), config.modelo());
            throw new LlmNoDisponibleException(MSG_NO_DISPONIBLE);
        } catch (ResourceAccessException e) {
            if (ErroresNube.esTimeout(e)) {
                log.warn("Timeout esperando a Ollama (modelo={}, imagenes={})", config.modelo(), cantImagenes);
                throw new LlmTimeoutException(MSG_TIMEOUT);
            }
            log.warn("No se pudo conectar con Ollama (modelo={})", config.modelo());
            throw new LlmNoDisponibleException(MSG_NO_DISPONIBLE);
        } catch (RestClientException e) {
            // Cuerpo no JSON / content-type inesperado: el mensaje podría contener la respuesta, no se loguea.
            log.warn("Respuesta de Ollama ilegible (modelo={}, tipo={})", config.modelo(), e.getClass().getSimpleName());
            throw new LlmNoDisponibleException(MSG_NO_DISPONIBLE);
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        JsonNode contenido = respuesta == null ? null : respuesta.path("message").path("content");
        if (contenido == null || !contenido.isTextual()) {
            log.warn("Respuesta de Ollama sin message.content (modelo={})", config.modelo());
            throw new LlmNoDisponibleException(MSG_NO_DISPONIBLE);
        }
        JsonNode promptEval = respuesta.path("prompt_eval_count");
        log.info("Ollama completó: modelo={}, imagenes={}, duracionMs={}, prompt_eval_count={}, eval_count={}",
            config.modelo(), cantImagenes, ms, promptEval.asText("n/d"), respuesta.path("eval_count").asText("n/d"));
        if (promptEval.isNumber() && promptEval.asInt() >= config.numCtx()) {
            log.warn("posible truncamiento de contexto: prompt_eval_count={} >= num_ctx={} (modelo={})",
                promptEval.asInt(), config.numCtx(), config.modelo());
        }
        if (logPrompts) {
            log.debug("ADVERTENCIA: extractor.debug.log-prompts activo. Respuesta: {}", contenido.asText());
        }
        if ("length".equals(respuesta.path("done_reason").asText(""))) {
            log.warn("El modelo alcanzó el límite de tokens: modelo={}, eval_count={}, num_predict={}",
                config.modelo(), respuesta.path("eval_count").asText("n/d"), config.numPredict());
            throw new LlmLimiteTokensException(MSG_LIMITE_TOKENS);
        }
        return contenido.asText();
    }

    private ObjectNode armarCuerpo(PromptLlm prompt) {
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("model", config.modelo());
        cuerpo.put("stream", false);
        cuerpo.set("format", prompt.esquema());
        cuerpo.putObject("options").put("temperature", 0).put("num_ctx", config.numCtx())
            .put("num_predict", config.numPredict());
        if (config.think() != null) {
            cuerpo.put("think", config.think().booleanValue());
        }
        ArrayNode mensajes = cuerpo.putArray("messages");
        mensajes.addObject().put("role", "system").put("content", prompt.sistema());
        ObjectNode usuario = mensajes.addObject();
        usuario.put("role", "user").put("content", prompt.usuario());
        if (prompt.imagenesBase64() != null && !prompt.imagenesBase64().isEmpty()) {
            ArrayNode imgs = usuario.putArray("images");
            prompt.imagenesBase64().forEach(imgs::add);
        }
        return cuerpo;
    }
}
