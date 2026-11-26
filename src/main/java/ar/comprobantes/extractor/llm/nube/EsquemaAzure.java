package ar.comprobantes.extractor.llm.nube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Adapta el esquema canónico a las restricciones del modo strict de Azure OpenAI. */
public final class EsquemaAzure {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private EsquemaAzure() {
    }

    /** Devuelve una copia adaptada; no muta el esquema canónico. */
    public static ObjectNode adaptar(ObjectNode canonico) {
        ObjectNode copia = canonico.deepCopy();
        if (copia.path("properties") instanceof ObjectNode props) {
            JsonNode detalle = props.path("detalle");
            if (detalle.path("maxItems").isNumber() && detalle.path("maxItems").asInt() == 0) {
                // Sin campos de detalle el parser ignora la propiedad: se quita en vez de pedirla vacía.
                props.remove("detalle");
            }
            props.path("clavesQr").path("properties")
                .forEach(p -> ((ObjectNode) p).set("type", F.arrayNode().add("array").add("null")));
        }
        estricto(copia);
        return copia;
    }

    /** Recorre por estructura de esquema (no por claves): los nombres de campo pueden coincidir con palabras clave. */
    private static void estricto(JsonNode nodo) {
        if (!(nodo instanceof ObjectNode obj)) {
            return;
        }
        if (obj.path("properties") instanceof ObjectNode props) {
            ArrayNode req = F.arrayNode();
            props.fieldNames().forEachRemaining(req::add);
            obj.set("required", req);
            obj.put("additionalProperties", false);
            props.forEach(EsquemaAzure::estricto);
        }
        estricto(obj.get("items"));
    }
}
