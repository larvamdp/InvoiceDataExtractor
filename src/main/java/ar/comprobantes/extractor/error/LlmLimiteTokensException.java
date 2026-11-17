package ar.comprobantes.extractor.error;

import org.springframework.http.HttpStatus;

/**
 * El modelo alcanzó el tope de tokens de salida (done_reason "length"). No extiende
 * {@link RespuestaLlmInvalidaException} a propósito: con temperatura 0 un reintento repetiría lo mismo.
 */
public class LlmLimiteTokensException extends ExtraccionException {

    public LlmLimiteTokensException(String mensaje) {
        super(mensaje);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    @Override
    public String titulo() {
        return "Respuesta del modelo inválida";
    }
}
