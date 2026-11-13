package ar.comprobantes.extractor.documento;

import ar.comprobantes.extractor.api.dto.ModoProcesamiento;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/** texto es null en modo IMAGEN; imagenesLlm es vacía en modo TEXTO; imagenesQr siempre está presente. */
public record DocumentoProcesado(
    TipoDocumento tipo,
    int paginas,
    ModoProcesamiento modo,
    String texto,
    List<BufferedImage> imagenesLlm,
    List<BufferedImage> imagenesQr,
    Map<String, Long> tiemposMs) {
}
