package ar.comprobantes.extractor.llm;

import java.util.List;

public record ValorLlm(String valor, String descripcion, List<String> clavesQr) {
}
