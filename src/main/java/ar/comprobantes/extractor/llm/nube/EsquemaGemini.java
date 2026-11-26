package ar.comprobantes.extractor.llm.nube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;

/** Adapta el esquema canónico al subconjunto OpenAPI de {@code responseSchema} de Gemini. */
public final class EsquemaGemini {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private EsquemaGemini() {
    }

    /** Devuelve una copia adaptada; no muta el esquema canónico. */
    public static ObjectNode adaptar(ObjectNode canonico) {
        ObjectNode copia = canonico.deepCopy();
        if (copia.path("properties") instanceof ObjectNode props) {
            JsonNode detalle = props.path("detalle");
            if (detalle.path("maxItems").isNumber() && detalle.path("maxItems").asInt() == 0) {
                // Sin campos de detalle el parser ignora la propiedad: se quita en vez de pedirla vacía.
                props.remove("detalle");
                if (copia.path("required") instanceof ArrayNode req) {
                    for (int i = req.size() - 1; i >= 0; i--) {
                        if ("detalle".equals(req.get(i).asText())) {
                            req.remove(i);
                        }
                    }
                }
            }
        }
        convertir(copia);
        return copia;
    }

    /** Recorre por estructura de esquema (no por claves): los nombres de campo pueden coincidir con palabras clave. */
    private static void convertir(JsonNode nodo) {
        if (!(nodo instanceof ObjectNode obj)) {
            return;
        }
        tipo(obj);
        if (obj.path("properties") instanceof ObjectNode props) {
            ArrayNode orden = F.arrayNode();
            props.fieldNames().forEachRemaining(orden::add);
            obj.set("propertyOrdering", orden);
            props.forEach(EsquemaGemini::convertir);
        }
        convertir(obj.get("items"));
        if (obj.has("enum") && !obj.has("type")) {
            obj.put("type", "STRING");
        }
    }

    private static void tipo(ObjectNode obj) {
        JsonNode tipo = obj.get("type");
        if (tipo == null) {
            return;
        }
        if (tipo.isArray()) {
            String principal = null;
            for (JsonNode t : tipo) {
                if ("null".equals(t.asText())) {
                    obj.put("nullable", true);
                } else if (principal == null) {
                    principal = t.asText();
                }
            }
            if (principal == null) {
                obj.remove("type");
            } else {
                obj.put("type", principal.toUpperCase(Locale.ROOT));
            }
        } else if (tipo.isTextual()) {
            obj.put("type", tipo.asText().toUpperCase(Locale.ROOT));
        }
    }
}
