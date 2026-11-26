package ar.comprobantes.extractor.llm.nube;

import ar.comprobantes.extractor.error.ExtraccionException;
import ar.comprobantes.extractor.error.LlmCuotaAgotadaException;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import ar.comprobantes.extractor.error.LlmTimeoutException;
import ar.comprobantes.extractor.error.RespuestaLlmInvalidaException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Traduce errores del cliente HTTP a excepciones de dominio, sin filtrar datos sensibles a logs ni mensajes. */
public final class ErroresNube {

    private static final Logger log = LoggerFactory.getLogger(ErroresNube.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MSG_NO_DISPONIBLE = "El servicio de IA no está disponible.";
    private static final String MSG_TIMEOUT = "El modelo excedió el tiempo máximo de respuesta.";
    private static final String MSG_CUOTA = "Se alcanzó el límite de uso del proveedor de IA.";
    private static final String MSG_BLOQUEADO = "El proveedor de IA bloqueó el contenido.";

    private ErroresNube() {
    }

    public static ExtraccionException traducir(Exception e, String proveedor, String modelo, boolean hayProxy) {
        if (e instanceof RestClientResponseException r) {
            return traducirRespuesta(r, proveedor, modelo);
        }
        if (e instanceof ResourceAccessException) {
            if (esTimeout(e)) {
                log.warn("timeout esperando al proveedor: proveedor={} modelo={}", proveedor, modelo);
                return new LlmTimeoutException(MSG_TIMEOUT);
            }
            if (esFalloDeAutenticacion(e)) {
                // Con Authenticator el JDK convierte 401/407 en IOException; el mensaje solo elige el log, no se registra.
                log.warn("autenticación rechazada por el proxy o el proveedor: proveedor={} modelo={}",
                    proveedor, modelo);
                return new LlmNoDisponibleException(MSG_NO_DISPONIBLE);
            }
            log.warn(hayProxy ? "no se pudo conectar (vía proxy): proveedor={} modelo={}"
                : "no se pudo conectar con el proveedor: proveedor={} modelo={}", proveedor, modelo);
            return new LlmNoDisponibleException(MSG_NO_DISPONIBLE);
        }
        log.warn("error inesperado del cliente HTTP: proveedor={} modelo={} tipo={}",
            proveedor, modelo, e.getClass().getSimpleName());
        return new LlmNoDisponibleException(MSG_NO_DISPONIBLE);
    }

    private static ExtraccionException traducirRespuesta(RestClientResponseException e, String proveedor,
                                                         String modelo) {
        int status = e.getStatusCode().value();
        if (status == 429) {
            Long espera = retryAfter(e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After"));
            log.warn("cuota agotada: proveedor={} modelo={} retryAfter={}", proveedor, modelo, espera);
            return new LlmCuotaAgotadaException(MSG_CUOTA, espera);
        }
        if (status == 401 || status == 403) {
            log.warn("credenciales rechazadas por el proveedor: proveedor={} status={}", proveedor, status);
            return new LlmNoDisponibleException(MSG_NO_DISPONIBLE);
        }
        if (status == 407) {
            log.warn("el proxy rechazó la autenticación");
            return new LlmNoDisponibleException(MSG_NO_DISPONIBLE);
        }
        String code = status == 400 || status == 422 ? errorCode(e) : null;
        if ("content_filter".equals(code)) {
            log.warn("contenido bloqueado por el proveedor: proveedor={} modelo={} status={}",
                proveedor, modelo, status);
            return new RespuestaLlmInvalidaException(MSG_BLOQUEADO);
        }
        if (code != null) {
            log.warn("el proveedor respondió HTTP {}: proveedor={} modelo={} code={}", status, proveedor, modelo, code);
        } else {
            log.warn("el proveedor respondió HTTP {}: proveedor={} modelo={}", status, proveedor, modelo);
        }
        return new LlmNoDisponibleException(MSG_NO_DISPONIBLE);
    }

    private static String errorCode(RestClientResponseException e) {
        try {
            JsonNode code = MAPPER.readTree(e.getResponseBodyAsString()).path("error").path("code");
            return code.isTextual() ? code.asText() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Long retryAfter(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String v = valor.trim();
        try {
            return Math.max(0L, Long.parseLong(v));
        } catch (NumberFormatException ignorada) {
            // puede ser una fecha HTTP
        }
        try {
            ZonedDateTime fecha = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0L, Duration.between(ZonedDateTime.now(), fecha).toSeconds());
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean esFalloDeAutenticacion(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c instanceof java.io.IOException ? c.getMessage() : null;
            if (m != null && (m.contains("too many authentication attempts") || m.contains("No credentials provided")
                || m.contains("header missing"))) {
                return true;
            }
        }
        return false;
    }

    public static boolean esTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof HttpConnectTimeoutException) {
                return false; // timeout de conexión: host inalcanzable, no es timeout de lectura
            }
            if (c instanceof HttpTimeoutException || c instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
