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

    @Test
    void esquemaTienePropiedadPorCampo() {
        ObjectNode e = esquema.para(solicitud(List.of(campo("descripcion"))), true);
        JsonNode cab = e.path("properties").path("cabecera");
        assertThat(cab.path("properties").has("numeroComprobante")).isTrue();
        assertThat(cab.path("properties").has("fechaEmision")).isTrue();
        JsonNode req = cab.path("properties").path("numeroComprobante").path("required");
        assertThat(req.toString()).contains("clavesQr", "valor", "descripcion");
        assertThat(e.path("required").toString()).contains("cabecera", "detalle");
        JsonNode det = e.path("properties").path("detalle");
        assertThat(det.path("type").asText()).isEqualTo("array");
        assertThat(det.path("items").path("properties").has("descripcion")).isTrue();
    }

    @Test
    void esquemaSinQrNoTieneClavesQr() {
        ObjectNode e = esquema.para(solicitud(List.of()), false);
        JsonNode c = e.path("properties").path("cabecera").path("properties").path("numeroComprobante");
        assertThat(c.path("properties").has("clavesQr")).isFalse();
        assertThat(c.path("required").toString()).doesNotContain("clavesQr");
    }

    @Test
    void esquemaSinDetalleEsArrayVacio() {
        ObjectNode e = esquema.para(solicitud(List.of()), true);
        assertThat(e.path("properties").path("detalle").path("maxItems").asInt(-1)).isZero();
    }
}
