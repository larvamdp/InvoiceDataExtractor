package ar.comprobantes.extractor.llm;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.error.RespuestaLlmInvalidaException;
import ar.comprobantes.extractor.qr.DatosQrArca;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        Map<String, ValorLlm> cabecera = campos(raiz.path("cabecera"), solicitud.camposCabecera());
        List<Map<String, ValorLlm>> detalle = new ArrayList<>();
        JsonNode filas = raiz.path("detalle");
        if (filas.isArray() && !solicitud.camposDetalle().isEmpty()) {
            for (JsonNode fila : filas) {
                detalle.add(campos(fila, solicitud.camposDetalle()));
            }
        }
        return new RespuestaLlm(cabecera, detalle);
    }

    private Map<String, ValorLlm> campos(JsonNode nodo, List<CampoSolicitado> pedidos) {
        Map<String, ValorLlm> res = new LinkedHashMap<>();
        for (CampoSolicitado c : pedidos) {
            res.put(c.nombre(), valor(nodo.path(c.nombre())));
        }
        return res;
    }

    private ValorLlm valor(JsonNode n) {
        if (!n.isObject()) {
            return noEncontrado();
        }
        String valor = texto(n.path("valor"));
        if (valor == null || valor.isBlank()) {
            return noEncontrado();
        }
        String descripcion = texto(n.path("descripcion"));
        List<String> claves = new ArrayList<>();
        JsonNode cq = n.path("clavesQr");
        if (cq.isArray()) {
            for (JsonNode k : cq) {
                if (k.isTextual() && DatosQrArca.CLAVES.contains(k.asText()) && !claves.contains(k.asText())) {
                    claves.add(k.asText());
                }
            }
        }
        return new ValorLlm(valor, descripcion, List.copyOf(claves));
    }

    private static String texto(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static ValorLlm noEncontrado() {
        return new ValorLlm(null, NO_ENCONTRADO, List.of());
    }
}
