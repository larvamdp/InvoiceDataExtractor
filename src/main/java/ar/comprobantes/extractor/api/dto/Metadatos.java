package ar.comprobantes.extractor.api.dto;

import java.util.Map;

public record Metadatos(String tipoDocumento, int paginas, ModoProcesamiento modoProcesamiento,
                        boolean verificacionTextual, String modelo, Map<String, Long> tiemposMs) {
}
