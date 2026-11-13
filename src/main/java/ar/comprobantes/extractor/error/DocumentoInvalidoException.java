package ar.comprobantes.extractor.error;

import org.springframework.http.HttpStatus;

public class DocumentoInvalidoException extends ExtraccionException {

    public DocumentoInvalidoException(String mensaje) {
        super(mensaje);
    }

    public DocumentoInvalidoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }

    @Override
    public String titulo() {
        return "Documento inválido";
    }
}
