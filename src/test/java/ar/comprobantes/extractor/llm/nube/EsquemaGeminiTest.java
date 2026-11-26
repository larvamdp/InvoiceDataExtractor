package ar.comprobantes.extractor.llm.nube;

import static org.assertj.core.api.Assertions.assertThat;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.api.dto.TipoCampo;
import ar.comprobantes.extractor.llm.EsquemaRespuesta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EsquemaGeminiTest {

    private final EsquemaRespuesta fuente = new EsquemaRespuesta();

    private static CampoSolicitado campo(String n) {
        return new CampoSolicitado(n, List.of(), null, TipoCampo.TEXTO);
    }

    private ObjectNode canonico(List<CampoSolicitado> detalle, boolean hayQr) {
        return fuente.para(new SolicitudExtraccion(List.of(campo("numeroComprobante"), campo("fechaEmision")), detalle),
            hayQr);
    }

    private static List<String> claves(JsonNode n) {
        List<String> r = new ArrayList<>();
        n.fieldNames().forEachRemaining(r::add);
        return r;
    }

    private static List<String> textos(JsonNode n) {
        List<String> r = new ArrayList<>();
        n.forEach(x -> r.add(x.asText()));
        return r;
    }

    @Test
    void tiposEnMayusculasYNullable() {
        ObjectNode e = EsquemaGemini.adaptar(canonico(List.of(campo("descripcion")), true));
        assertThat(e.path("type").asText()).isEqualTo("OBJECT");
        JsonNode cab = e.path("properties").path("cabecera");
        assertThat(cab.path("type").asText()).isEqualTo("OBJECT");
        JsonNode num = cab.path("properties").path("numeroComprobante");
        assertThat(num.path("type").asText()).isEqualTo("STRING");
        assertThat(num.path("nullable").asBoolean()).isTrue();
        JsonNode detalle = e.path("properties").path("detalle");
        assertThat(detalle.path("type").asText()).isEqualTo("ARRAY");
        assertThat(detalle.path("items").path("type").asText()).isEqualTo("OBJECT");
        assertThat(detalle.path("items").path("properties").path("descripcion").path("type").asText())
            .isEqualTo("STRING");
    }

    @Test
    void propertyOrderingEnTodosLosObjetos() {
        ObjectNode e = EsquemaGemini.adaptar(canonico(List.of(campo("descripcion")), true));
        assertThat(textos(e.path("propertyOrdering"))).containsExactly("cabecera", "clavesQr", "detalle");
        JsonNode props = e.path("properties");
        assertThat(textos(props.path("cabecera").path("propertyOrdering")))
            .containsExactly("numeroComprobante", "fechaEmision");
        assertThat(textos(props.path("clavesQr").path("propertyOrdering")))
            .containsExactly("numeroComprobante", "fechaEmision");
        assertThat(textos(props.path("detalle").path("items").path("propertyOrdering")))
            .containsExactly("descripcion");
        assertThat(textos(e.path("required"))).containsExactly("cabecera", "clavesQr", "detalle");
        assertThat(e.has("additionalProperties")).isFalse();
    }

    @Test
    void enumDeClavesQrSeConserva() {
        ObjectNode e = EsquemaGemini.adaptar(canonico(List.of(campo("descripcion")), true));
        JsonNode arr = e.path("properties").path("clavesQr").path("properties").path("numeroComprobante");
        assertThat(arr.path("type").asText()).isEqualTo("ARRAY");
        assertThat(arr.path("items").path("enum").size()).isPositive();
        assertThat(arr.path("items").path("type").asText()).isEqualTo("STRING");
    }

    @Test
    void sinCamposDeDetalleSeQuitaDetalle() {
        ObjectNode e = EsquemaGemini.adaptar(canonico(List.of(), true));
        assertThat(e.path("properties").has("detalle")).isFalse();
        assertThat(textos(e.path("propertyOrdering"))).containsExactly("cabecera", "clavesQr");
        assertThat(textos(e.path("required"))).containsExactly("cabecera", "clavesQr");
    }

    @Test
    void noMutaElOriginal() {
        ObjectNode original = canonico(List.of(), true);
        String antes = original.toString();
        EsquemaGemini.adaptar(original);
        assertThat(original.toString()).isEqualTo(antes);
    }

    private void verificarObjeto(JsonNode schema, List<String> esperadas) {
        assertThat(schema.path("type").asText()).isEqualTo("OBJECT");
        assertThat(claves(schema.path("properties"))).containsExactlyElementsOf(esperadas);
        assertThat(textos(schema.path("propertyOrdering"))).containsExactlyElementsOf(esperadas);
    }

    @Test
    void camposConNombresDeVocabularioJsonSchemaSeAdaptanBien() {
        List<CampoSolicitado> campos = List.of(campo("properties"), campo("required"), campo("type"));
        List<String> nombres = List.of("properties", "required", "type");
        for (boolean hayQr : new boolean[] {true, false}) {
            ObjectNode e = EsquemaGemini.adaptar(fuente.para(new SolicitudExtraccion(campos, campos), hayQr));
            JsonNode props = e.path("properties");
            verificarObjeto(e, hayQr ? List.of("cabecera", "clavesQr", "detalle") : List.of("cabecera", "detalle"));
            verificarObjeto(props.path("cabecera"), nombres);
            nombres.forEach(n -> {
                JsonNode c = props.path("cabecera").path("properties").path(n);
                assertThat(c.path("type").asText()).isEqualTo("STRING");
                assertThat(c.path("nullable").asBoolean()).isTrue();
            });
            verificarObjeto(props.path("detalle").path("items"), nombres);
            nombres.forEach(n -> assertThat(
                props.path("detalle").path("items").path("properties").path(n).path("type").asText())
                .isEqualTo("STRING"));
            if (hayQr) {
                verificarObjeto(props.path("clavesQr"), nombres);
                nombres.forEach(n -> assertThat(
                    props.path("clavesQr").path("properties").path(n).path("type").asText()).isEqualTo("ARRAY"));
            }
        }
    }
}
