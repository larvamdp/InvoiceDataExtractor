package ar.comprobantes.extractor.llm;

import static org.assertj.core.api.Assertions.assertThat;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.api.dto.TipoCampo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class EsquemaRespuestaTest {

    private final EsquemaRespuesta esquema = new EsquemaRespuesta();

    private static CampoSolicitado campo(String n) {
        return new CampoSolicitado(n, List.of(), null, TipoCampo.TEXTO);
    }

    private static SolicitudExtraccion solicitud(List<CampoSolicitado> detalle) {
        return new SolicitudExtraccion(List.of(campo("numeroComprobante"), campo("fechaEmision")), detalle);
    }

    private static final String STRING_O_NULL = "{\"type\":[\"string\",\"null\"]}";

    @Test
    void esquemaTienePropiedadPorCampo() {
        ObjectNode e = esquema.para(solicitud(List.of(campo("descripcion"))), true);
        JsonNode cab = e.path("properties").path("cabecera");
        assertThat(cab.path("properties").path("numeroComprobante").toString()).isEqualTo(STRING_O_NULL);
        assertThat(cab.path("properties").path("fechaEmision").toString()).isEqualTo(STRING_O_NULL);
        assertThat(cab.path("required").toString()).contains("numeroComprobante", "fechaEmision");
        JsonNode det = e.path("properties").path("detalle");
        assertThat(det.path("type").asText()).isEqualTo("array");
        assertThat(det.path("items").path("properties").path("descripcion").toString()).isEqualTo(STRING_O_NULL);
        assertThat(det.path("items").path("required").toString()).contains("descripcion");
    }

    @Test
    void clavesQrEsMapaOpcionalPorCampoDeCabecera() {
        ObjectNode e = esquema.para(solicitud(List.of(campo("descripcion"))), true);
        JsonNode cq = e.path("properties").path("clavesQr");
        assertThat(cq.path("type").asText()).isEqualTo("object");
        assertThat(cq.has("required")).isFalse();
        assertThat(cq.path("properties").has("numeroComprobante")).isTrue();
        assertThat(cq.path("properties").has("descripcion")).isFalse();
        JsonNode items = cq.path("properties").path("numeroComprobante");
        assertThat(items.path("type").asText()).isEqualTo("array");
        List<String> en = new java.util.ArrayList<>();
        items.path("items").path("enum").forEach(n -> en.add(n.asText()));
        assertThat(en).containsExactlyElementsOf(ar.comprobantes.extractor.qr.DatosQrArca.CLAVES);
    }

    @Test
    void conQrClavesQrEsObligatorioYVaAntesDelDetalle() {
        ObjectNode e = esquema.para(solicitud(List.of(campo("descripcion"))), true);
        assertThat(e.path("required").toString()).isEqualTo("[\"cabecera\",\"clavesQr\",\"detalle\"]");
        List<String> orden = new java.util.ArrayList<>();
        e.path("properties").fieldNames().forEachRemaining(orden::add);
        assertThat(orden).containsExactly("cabecera", "clavesQr", "detalle");
    }

    @Test
    void esquemaSinQrNoTieneClavesQr() {
        ObjectNode e = esquema.para(solicitud(List.of()), false);
        assertThat(e.path("properties").has("clavesQr")).isFalse();
        assertThat(e.path("required").toString()).isEqualTo("[\"cabecera\",\"detalle\"]");
    }

    @Test
    void esquemaSinDetalleEsArrayVacio() {
        ObjectNode e = esquema.para(solicitud(List.of()), true);
        assertThat(e.path("properties").path("detalle").path("maxItems").asInt(-1)).isZero();
    }
}
