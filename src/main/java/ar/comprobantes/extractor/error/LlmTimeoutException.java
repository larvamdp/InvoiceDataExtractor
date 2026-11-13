package ar.comprobantes.extractor.error;

import org.springframework.http.HttpStatus;

public class LlmTimeoutException extends ExtraccionException {

    public LlmTimeoutException(String mensaje) {
        super(mensaje);
    }

    public LlmTimeoutException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.GATEWAY_TIMEOUT;
    }

    @Override
    public String titulo() {
        return "Tiempo de espera del modelo agotado";
    }
}
