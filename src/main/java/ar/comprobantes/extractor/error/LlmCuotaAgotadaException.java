package ar.comprobantes.extractor.error;

import org.springframework.http.HttpStatus;

/** El proveedor de IA respondió 429. {@code retryAfterSegundos} nulo significa que no hay header Retry-After. */
public class LlmCuotaAgotadaException extends ExtraccionException {

    private final Long retryAfterSegundos;

    public LlmCuotaAgotadaException(String mensaje, Long retryAfterSegundos) {
        super(mensaje);
        this.retryAfterSegundos = retryAfterSegundos;
    }

    public Long retryAfterSegundos() {
        return retryAfterSegundos;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.SERVICE_UNAVAILABLE;
    }

    @Override
    public String titulo() {
        return "Modelo de lenguaje no disponible";
    }
}
