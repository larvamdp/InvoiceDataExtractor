package ar.comprobantes.extractor.api.dto;

import ar.comprobantes.extractor.qr.DatosQrArca;

public record QrArcaResultado(boolean presente, DatosQrArca datos, String descripcion) {
}
