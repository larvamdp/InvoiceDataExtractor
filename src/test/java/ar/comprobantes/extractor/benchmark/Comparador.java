package ar.comprobantes.extractor.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Compara el JSON esperado del dataset contra la respuesta del servicio. */
public final class Comparador {

    public record ResultadoComparacion(int camposTotales, int aciertos, int inventados, int omitidos,
                                       List<String> errores) {}

    private Comparador() {}

    /**
     * @param esperado forma {@code {cabecera:{campo:valor|null}, detalle:[{campo:valor|null}]}}
     * @param obtenido respuesta del servicio ({@code cabecera.<campo>.valor}, {@code detalle[i].<campo>.valor})
     */
    @SuppressWarnings("unchecked")
    public static ResultadoComparacion comparar(Map<String, Object> esperado, JsonNode obtenido) {
        int[] c = new int[4]; // total, aciertos, inventados, omitidos
        List<String> errores = new ArrayList<>();

        Map<String, Object> cab = (Map<String, Object>) esperado.get("cabecera");
        if (cab != null) {
            for (var e : cab.entrySet()) {
                JsonNode nodo = obtenido == null ? null : obtenido.path("cabecera").path(e.getKey());
                campo("cabecera." + e.getKey(), e.getValue(), nodo, c, errores);
            }
        }
        List<Map<String, Object>> det = (List<Map<String, Object>>) esperado.get("detalle");
        if (det != null) {
            JsonNode filas = obtenido == null ? null : obtenido.path("detalle");
            int nObt = filas != null && filas.isArray() ? filas.size() : 0;
            for (int i = 0; i < det.size(); i++) {
                for (var e : det.get(i).entrySet()) {
                    JsonNode nodo = i < nObt ? filas.get(i).path(e.getKey()) : null;
                    campo("detalle[" + i + "]." + e.getKey(), e.getValue(), nodo, c, errores);
                }
            }
            // filas sobrantes: cada campo con valor cuenta como inventado
            for (int i = det.size(); i < nObt; i++) {
                var it = filas.get(i).fields();
                while (it.hasNext()) {
                    var e = it.next();
                    JsonNode v = e.getValue().path("valor");
                    c[0]++;
                    if (!v.isMissingNode() && !v.isNull()) {
                        c[2]++;
                        errores.add("detalle[" + i + "]." + e.getKey() + ": fila sobrante");
                    }
                }
            }
        }
        return new ResultadoComparacion(c[0], c[1], c[2], c[3], errores);
    }

    private static void campo(String ruta, Object esperado, JsonNode nodo, int[] c, List<String> errores) {
        c[0]++;
        JsonNode valor = nodo == null ? null : nodo.path("valor");
        boolean hay = valor != null && !valor.isMissingNode() && !valor.isNull();
        if (esperado == null) {
            if (hay) {
                c[2]++;
                errores.add(ruta + ": inventado (" + valor.asText() + ")");
            } else {
                c[1]++;
            }
            return;
        }
        if (!hay) {
            c[3]++;
            errores.add(ruta + ": omitido (esperado " + esperado + ")");
            return;
        }
        if (iguales(esperado, valor)) {
            c[1]++;
        } else {
            errores.add(ruta + ": esperado " + esperado + " obtenido " + valor.asText());
        }
    }

    private static boolean iguales(Object esperado, JsonNode obtenido) {
        if (esperado instanceof Number n) {
            try {
                BigDecimal o = obtenido.isNumber() ? obtenido.decimalValue() : new BigDecimal(obtenido.asText().trim());
                return new BigDecimal(n.toString()).compareTo(o) == 0;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return normalizar(esperado.toString()).equals(normalizar(obtenido.asText()));
    }

    private static String normalizar(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
