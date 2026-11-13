package ar.comprobantes.extractor.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SerializacionDtoTest {

    @Test
    void campoSinTipoEsTexto() {
        var c = new CampoSolicitado("nro", null, null, null);
        assertThat(c.tipo()).isEqualTo(TipoCampo.TEXTO);
        assertThat(c.etiquetas()).isEmpty();
    }

    @Test
    void solicitudSinListasUsaListasVacias() {
        var s = new SolicitudExtraccion(null, null);
        assertThat(s.camposCabecera()).isEmpty();
        assertThat(s.camposDetalle()).isEmpty();
    }

    @Test
    void valorNoEncontradoSerializaNulls() throws Exception {
        String json = new ObjectMapper().writeValueAsString(ValorCampo.noEncontrado());
        assertThat(json).isEqualTo("{\"valor\":null,\"descripcion\":\"Dato no encontrado.\",\"fuente\":null}");
    }

    @Test
    void deserializaSolicitudDelDiseno() throws Exception {
        String json = """
            {
              "camposCabecera": [
                {
                  "nombre": "numeroComprobante",
                  "etiquetas": ["Número factura", "Número de factura", "Nro. factura", "Factura N°", "Comp. Nro"],
                  "descripcion": "Número completo del comprobante, incluyendo punto de venta",
                  "tipo": "TEXTO"
                },
                { "nombre": "fechaEmision", "etiquetas": ["Fecha", "Fecha de emisión"], "tipo": "FECHA" },
                { "nombre": "importeTotal", "etiquetas": ["Total", "Importe total"], "tipo": "IMPORTE" }
              ],
              "camposDetalle": [
                { "nombre": "concepto", "etiquetas": ["Concepto", "Descripción", "Detalle"], "tipo": "TEXTO" },
                { "nombre": "cantidad", "etiquetas": ["Cant.", "Cantidad"], "tipo": "NUMERO" },
                { "nombre": "precioUnitario", "etiquetas": ["P. Unit.", "Precio unitario"], "tipo": "IMPORTE" }
              ]
            }
            """;
        var s = new ObjectMapper().readValue(json, SolicitudExtraccion.class);
        assertThat(s.camposCabecera()).extracting(CampoSolicitado::tipo)
            .containsExactly(TipoCampo.TEXTO, TipoCampo.FECHA, TipoCampo.IMPORTE);
        assertThat(s.camposDetalle()).extracting(CampoSolicitado::tipo)
            .containsExactly(TipoCampo.TEXTO, TipoCampo.NUMERO, TipoCampo.IMPORTE);
        assertThat(s.camposCabecera().get(0).etiquetas()).hasSize(5);
        assertThat(s.camposCabecera().get(1).descripcion()).isNull();
    }
}
