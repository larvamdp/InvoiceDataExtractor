package ar.comprobantes.extractor.llm;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.error.RespuestaLlmInvalidaException;
import ar.comprobantes.extractor.qr.DatosQrArca;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ParserRespuestaLlm {

    static final String NO_ENCONTRADO = "Dato no encontrado.";

    private final ObjectMapper mapper;

    public ParserRespuestaLlm(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public RespuestaLlm parsear(String json, SolicitudExtraccion solicitud) {
        JsonNode raiz;
        try {
            raiz = mapper.readTree(json);
        } catch (JsonProcessingException | RuntimeException e) {
            // No se incluye el contenido ni la causa: puede contener datos del documento.
            throw new RespuestaLlmInvalidaException("El modelo devolvió una respuesta que no es JSON válido.");
        }
        if (raiz == null || !raiz.isObject()) {
            throw new RespuestaLlmInvalidaException("El modelo devolvió una respuesta con estructura inesperada.");
        }
        Map<String, ValorLlm> cabecera = campos(raiz.path("cabecera"), solicitud.camposCabecera(),
            raiz.path("clavesQr"));
        List<Map<String, ValorLlm>> detalle = new ArrayList<>();
        JsonNode filas = raiz.path("detalle");
        if (filas.isArray() && !solicitud.camposDetalle().isEmpty()) {
            for (JsonNode fila : filas) {
                detalle.add(campos(fila, solicitud.camposDetalle(), MissingNode.getInstance()));
            }
        }
        return new RespuestaLlm(cabecera, detalle);
    }

    private Map<String, ValorLlm> campos(JsonNode nodo, List<CampoSolicitado> pedidos, JsonNode clavesQr) {
        Map<String, ValorLlm> res = new LinkedHashMap<>();
        for (CampoSolicitado c : pedidos) {
            res.put(c.nombre(), valor(nodo.path(c.nombre()), clavesQr.path(c.nombre())));
        }
        return res;
    }

    /** Valor directo (texto o número); vacío, "null", null u otro tipo cuentan como no encontrado. */
    private ValorLlm valor(JsonNode n, JsonNode cq) {
        String valor = n.isTextual() || n.isNumber() ? n.asText().trim() : null;
        if (valor == null || valor.isEmpty() || valor.equalsIgnoreCase("null")) {
            return noEncontrado();
        }
        List<String> claves = new ArrayList<>();
        if (cq.isArray()) {
            for (JsonNode k : cq) {
                if (k.isTextual() && DatosQrArca.CLAVES.contains(k.asText()) && !claves.contains(k.asText())) {
                    claves.add(k.asText());
                }
            }
        }
        return new ValorLlm(valor, null, List.copyOf(claves));
    }

    private static ValorLlm noEncontrado() {
        return new ValorLlm(null, NO_ENCONTRADO, List.of());
    }
}
