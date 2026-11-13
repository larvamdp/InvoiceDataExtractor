package ar.comprobantes.extractor.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public record PromptLlm(String sistema, String usuario, List<String> imagenesBase64, ObjectNode esquema) {
}
