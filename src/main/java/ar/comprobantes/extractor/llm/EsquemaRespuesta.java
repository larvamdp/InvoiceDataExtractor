package ar.comprobantes.extractor.llm;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.qr.DatosQrArca;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EsquemaRespuesta {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public ObjectNode para(SolicitudExtraccion solicitud, boolean hayQr) {
        ObjectNode raiz = F.objectNode();
        raiz.put("type", "object");
        raiz.set("required", textos("cabecera", "detalle"));
        ObjectNode props = raiz.putObject("properties");
        props.set("cabecera", cabecera(solicitud.camposCabecera(), hayQr));
        props.set("detalle", detalle(solicitud.camposDetalle()));
        return raiz;
    }

    private ObjectNode cabecera(List<CampoSolicitado> campos, boolean hayQr) {
        ObjectNode obj = F.objectNode();
        obj.put("type", "object");
        ObjectNode props = obj.putObject("properties");
        ArrayNode req = obj.putArray("required");
        for (CampoSolicitado c : campos) {
            props.set(c.nombre(), valor(hayQr));
            req.add(c.nombre());
        }
        return obj;
    }

    private ObjectNode detalle(List<CampoSolicitado> campos) {
        ObjectNode arr = F.objectNode();
        arr.put("type", "array");
        if (campos.isEmpty()) {
            arr.put("maxItems", 0);
            return arr;
        }
        ObjectNode items = arr.putObject("items");
        items.put("type", "object");
        ObjectNode props = items.putObject("properties");
        ArrayNode req = items.putArray("required");
        for (CampoSolicitado c : campos) {
            props.set(c.nombre(), valor(false));
            req.add(c.nombre());
        }
        return arr;
    }

    private ObjectNode valor(boolean conClavesQr) {
        ObjectNode obj = F.objectNode();
        obj.put("type", "object");
        ObjectNode props = obj.putObject("properties");
        props.set("valor", stringONull());
        props.set("descripcion", stringONull());
        if (conClavesQr) {
            ObjectNode claves = props.putObject("clavesQr");
            claves.put("type", "array");
            ArrayNode en = claves.putObject("items").putArray("enum");
            DatosQrArca.CLAVES.forEach(en::add);
            obj.set("required", textos("valor", "descripcion", "clavesQr"));
        } else {
            obj.set("required", textos("valor", "descripcion"));
        }
        return obj;
    }

    private ObjectNode stringONull() {
        ObjectNode n = F.objectNode();
        n.set("type", textos("string", "null"));
        return n;
    }

    private ArrayNode textos(String... valores) {
        ArrayNode a = F.arrayNode();
        for (String v : valores) {
            a.add(v);
        }
        return a;
    }
}
