package ar.comprobantes.extractor.api;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.error.DocumentoInvalidoException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ValidadorSolicitud {

    private static final Pattern NOMBRE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    public void validar(SolicitudExtraccion solicitud) {
        if (solicitud == null || (solicitud.camposCabecera().isEmpty() && solicitud.camposDetalle().isEmpty())) {
            throw new DocumentoInvalidoException("La solicitud no contiene campos.");
        }
        validarLista(solicitud.camposCabecera(), "cabecera");
        validarLista(solicitud.camposDetalle(), "detalle");
    }

    private void validarLista(List<CampoSolicitado> campos, String seccion) {
        Set<String> vistos = new HashSet<>();
        for (CampoSolicitado campo : campos) {
            String nombre = campo == null ? null : campo.nombre();
            if (nombre == null || !NOMBRE.matcher(nombre).matches()) {
                throw new DocumentoInvalidoException("El nombre de campo '" + nombre
                    + "' no es válido: use letras, números y guión bajo, comenzando con letra.");
            }
            if (!vistos.add(nombre)) {
                throw new DocumentoInvalidoException(
                    "El nombre de campo '" + nombre + "' está duplicado en " + seccion + ".");
            }
        }
    }
}
