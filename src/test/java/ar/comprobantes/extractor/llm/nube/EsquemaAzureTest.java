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

class EsquemaAzureTest {

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

    private void verificarEstricto(JsonNode n) {
        if (n.isObject()) {
            if (n.has("properties")) {
                assertThat(n.path("additionalProperties").isBoolean()).isTrue();
                assertThat(n.path("additionalProperties").asBoolean()).isFalse();
                assertThat(textos(n.path("required"))).containsExactlyElementsOf(claves(n.path("properties")));
            }
            n.forEach(this::verificarEstricto);
        } else if (n.isArray()) {
            n.forEach(this::verificarEstricto);
        }
    }

    @Test
    void todosLosObjetosSonEstrictos() {
        verificarEstricto(EsquemaAzure.adaptar(canonico(List.of(campo("descripcion")), true)));
        verificarEstricto(EsquemaAzure.adaptar(canonico(List.of(campo("descripcion")), false)));
        verificarEstricto(EsquemaAzure.adaptar(canonico(List.of(), true)));
    }

    @Test
    void clavesQrNullable() {
        ObjectNode e = EsquemaAzure.adaptar(canonico(List.of(campo("descripcion")), true));
        JsonNode cq = e.path("properties").path("clavesQr");
        assertThat(textos(cq.path("required"))).containsExactly("numeroComprobante", "fechaEmision");
        cq.path("properties").forEach(p -> assertThat(p.path("type").toString()).isEqualTo("[\"array\",\"null\"]"));
        assertThat(cq.path("properties").path("numeroComprobante").path("items").path("enum").size()).isPositive();
    }

    @Test
    void sinCamposDeDetalleSeQuitaDetalle() {
        ObjectNode e = EsquemaAzure.adaptar(canonico(List.of(), true));
        assertThat(e.path("properties").has("detalle")).isFalse();
        assertThat(textos(e.path("required"))).containsExactly("cabecera", "clavesQr");
        ObjectNode sinQr = EsquemaAzure.adaptar(canonico(List.of(), false));
        assertThat(textos(sinQr.path("required"))).containsExactly("cabecera");
    }

    @Test
    void conservaOrdenCabeceraClavesQrDetalle() {
        ObjectNode e = EsquemaAzure.adaptar(canonico(List.of(campo("descripcion")), true));
        assertThat(claves(e.path("properties"))).containsExactly("cabecera", "clavesQr", "detalle");
        assertThat(claves(e.path("properties").path("cabecera").path("properties")))
            .containsExactly("numeroComprobante", "fechaEmision");
    }

    @Test
    void noMutaElOriginal() {
        ObjectNode original = canonico(List.of(), true);
        String antes = original.toString();
        EsquemaAzure.adaptar(original);
        assertThat(original.toString()).isEqualTo(antes);
    }

    private void verificarEstructura(JsonNode schema, List<String> esperadas) {
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(textos(schema.path("required"))).containsExactlyElementsOf(esperadas);
        assertThat(claves(schema.path("properties"))).containsExactlyElementsOf(esperadas);
    }

    @Test
    void camposConNombresDeVocabularioJsonSchemaSiguenEstrictos() {
        List<CampoSolicitado> campos = List.of(campo("properties"), campo("required"), campo("type"));
        List<String> nombres = List.of("properties", "required", "type");
        for (boolean hayQr : new boolean[] {true, false}) {
            ObjectNode e = EsquemaAzure.adaptar(
                fuente.para(new SolicitudExtraccion(campos, campos), hayQr));
            JsonNode props = e.path("properties");
            verificarEstructura(e, hayQr ? List.of("cabecera", "clavesQr", "detalle") : List.of("cabecera", "detalle"));
            verificarEstructura(props.path("cabecera"), nombres);
            nombres.forEach(n -> assertThat(props.path("cabecera").path("properties").path(n).path("type").toString())
                .isEqualTo("[\"string\",\"null\"]"));
            verificarEstructura(props.path("detalle").path("items"), nombres);
            if (hayQr) {
                verificarEstructura(props.path("clavesQr"), nombres);
                nombres.forEach(n -> assertThat(props.path("clavesQr").path("properties").path(n).path("type").toString())
                    .isEqualTo("[\"array\",\"null\"]"));
            }
        }
    }

    private void todoNodoTieneTipo(JsonNode n, String ruta) {
        if (n.isObject()) {
            if (n.has("enum")) {
                assertThat(n.has("type")).as("enum sin type en " + ruta).isTrue();
            }
            n.fields().forEachRemaining(e -> todoNodoTieneTipo(e.getValue(), ruta + "/" + e.getKey()));
        } else if (n.isArray()) {
            n.forEach(x -> todoNodoTieneTipo(x, ruta));
        }
    }

    @Test
    void itemsConEnumLlevanTipoString() {
        ObjectNode e = EsquemaAzure.adaptar(canonico(List.of(campo("descripcion")), true));
        todoNodoTieneTipo(e, "");
        assertThat(e.path("properties").path("clavesQr").path("properties").path("numeroComprobante")
            .path("items").path("type").asText()).isEqualTo("string");
    }

    @Test
    void soloDetalleQuitaCabeceraYClavesQrVacias() {
        List<CampoSolicitado> det = List.of(campo("descripcion"));
        for (boolean hayQr : new boolean[] {true, false}) {
            ObjectNode e = EsquemaAzure.adaptar(fuente.para(new SolicitudExtraccion(List.of(), det), hayQr));
            assertThat(claves(e.path("properties"))).containsExactly("detalle");
            assertThat(textos(e.path("required"))).containsExactly("detalle");
        }
    }
}
