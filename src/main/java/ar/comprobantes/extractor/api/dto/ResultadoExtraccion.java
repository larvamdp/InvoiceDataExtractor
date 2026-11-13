package ar.comprobantes.extractor.api.dto;

import java.util.List;
import java.util.Map;

public record ResultadoExtraccion(Map<String, ValorCampo> cabecera, List<Map<String, ValorCampo>> detalle,
                                  QrArcaResultado qrArca, Metadatos metadatos) {
}
