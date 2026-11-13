package ar.comprobantes.extractor.api.dto;

import java.util.List;

public record CampoSolicitado(String nombre, List<String> etiquetas, String descripcion, TipoCampo tipo) {

    public CampoSolicitado {
        etiquetas = etiquetas == null ? List.of() : List.copyOf(etiquetas);
        tipo = tipo == null ? TipoCampo.TEXTO : tipo;
    }
}
