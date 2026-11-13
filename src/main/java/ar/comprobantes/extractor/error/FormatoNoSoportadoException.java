package ar.comprobantes.extractor.error;

import org.springframework.http.HttpStatus;

public class FormatoNoSoportadoException extends ExtraccionException {

    public FormatoNoSoportadoException(String mensaje) {
        super(mensaje);
    }

    public FormatoNoSoportadoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNSUPPORTED_MEDIA_TYPE;
    }

    @Override
    public String titulo() {
        return "Formato no soportado";
    }
}
