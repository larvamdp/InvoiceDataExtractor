package ar.comprobantes.extractor.normalizacion;

/** Valor normalizado y, si hubo algo que informar, una observación (null si todo OK). */
public record ResultadoNormalizacion(Object valor, String observacion) {
}
