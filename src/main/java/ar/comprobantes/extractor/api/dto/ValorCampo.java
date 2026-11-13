package ar.comprobantes.extractor.api.dto;

public record ValorCampo(Object valor, String descripcion, Fuente fuente) {

    public static ValorCampo noEncontrado() {
        return new ValorCampo(null, "Dato no encontrado.", null);
    }
}
