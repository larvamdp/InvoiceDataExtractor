package ar.comprobantes.extractor.error;

import org.springframework.http.HttpStatus;

public class DocumentoDemasiadoGrandeException extends ExtraccionException {

    public DocumentoDemasiadoGrandeException(String mensaje) {
        super(mensaje);
    }

    public DocumentoDemasiadoGrandeException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.PAYLOAD_TOO_LARGE;
    }

    @Override
    public String titulo() {
        return "Documento demasiado grande";
    }
}
