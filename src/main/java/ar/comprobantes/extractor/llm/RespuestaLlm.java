package ar.comprobantes.extractor.llm;

import java.util.List;
import java.util.Map;

public record RespuestaLlm(Map<String, ValorLlm> cabecera, List<Map<String, ValorLlm>> detalle) {
}
