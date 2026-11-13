package ar.comprobantes.extractor.api.dto;

import java.util.List;

public record SolicitudExtraccion(List<CampoSolicitado> camposCabecera, List<CampoSolicitado> camposDetalle) {

    public SolicitudExtraccion {
        camposCabecera = camposCabecera == null ? List.of() : List.copyOf(camposCabecera);
        camposDetalle = camposDetalle == null ? List.of() : List.copyOf(camposDetalle);
    }
}
