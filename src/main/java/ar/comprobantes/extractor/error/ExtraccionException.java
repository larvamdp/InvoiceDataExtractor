package ar.comprobantes.extractor.error;

import org.springframework.http.HttpStatus;

/** Error de negocio con estado HTTP. Los mensajes nunca incluyen contenido del documento ni del LLM. */
public abstract class ExtraccionException extends RuntimeException {

    protected ExtraccionException(String mensaje) {
        super(mensaje);
    }

    protected ExtraccionException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    public abstract HttpStatus status();

    public abstract String titulo();
}
