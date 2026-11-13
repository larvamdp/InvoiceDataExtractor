package ar.comprobantes.extractor.documento;

import ar.comprobantes.extractor.error.FormatoNoSoportadoException;
import org.springframework.stereotype.Component;

@Component
public class DetectorTipoDocumento {

    public TipoDocumento detectar(byte[] contenido) {
        if (empieza(contenido, '%', 'P', 'D', 'F', '-')) {
            return TipoDocumento.PDF;
        }
        if (empieza(contenido, 0xFF, 0xD8, 0xFF)) {
            return TipoDocumento.JPEG;
        }
        if (empieza(contenido, 0x89, 0x50, 0x4E, 0x47)) {
            return TipoDocumento.PNG;
        }
        throw new FormatoNoSoportadoException("El documento no es PDF, JPG ni PNG.");
    }

    private static boolean empieza(byte[] datos, int... prefijo) {
        if (datos == null || datos.length < prefijo.length) {
            return false;
        }
        for (int i = 0; i < prefijo.length; i++) {
            if ((datos[i] & 0xFF) != prefijo[i]) {
                return false;
            }
        }
        return true;
    }
}
