package ar.comprobantes.extractor.error;

import org.springframework.http.HttpStatus;

public class RespuestaLlmInvalidaException extends ExtraccionException {

    public RespuestaLlmInvalidaException(String mensaje) {
        super(mensaje);
    }

    public RespuestaLlmInvalidaException(String mensaje, Throwable causa) {
        super(mensaje, causa);
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
