package ar.comprobantes.extractor.error;

import org.springframework.http.HttpStatus;

public class LlmNoDisponibleException extends ExtraccionException {

    public LlmNoDisponibleException(String mensaje) {
        super(mensaje);
    }

    public LlmNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
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
