package ar.comprobantes.extractor.error;

import org.springframework.http.HttpStatus;

public class ServicioOcupadoException extends ExtraccionException {

    public ServicioOcupadoException(String mensaje) {
        super(mensaje);
    }

    public ServicioOcupadoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.SERVICE_UNAVAILABLE;
    }

    @Override
    public String titulo() {
        return "Servicio ocupado";
    }
}
