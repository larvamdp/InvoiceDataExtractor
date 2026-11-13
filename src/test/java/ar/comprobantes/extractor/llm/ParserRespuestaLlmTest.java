package ar.comprobantes.extractor.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.api.dto.TipoCampo;
import ar.comprobantes.extractor.error.RespuestaLlmInvalidaException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserRespuestaLlmTest {

    private final ParserRespuestaLlm parser = new ParserRespuestaLlm(new ObjectMapper());

    private static CampoSolicitado campo(String n) {
        return new CampoSolicitado(n, List.of(), null, TipoCampo.TEXTO);
    }

    private final SolicitudExtraccion solicitud = new SolicitudExtraccion(
            List.of(campo("numeroComprobante"), campo("fechaEmision")), List.of(campo("descripcion")));

    @Test
    void parseaRespuestaCompleta() {
        String json = """
                {"cabecera":{
                  "numeroComprobante":{"valor":"0001-00012345","descripcion":null,"clavesQr":["ptoVta","nroCmp"]},
                  "fechaEmision":{"valor":"01/05/2024","descripcion":"Se usó la fecha de emisión.","clavesQr":[]}},
                 "detalle":[{"descripcion":{"valor":"Tornillos","descripcion":null}},
                            {"descripcion":{"valor":"Tuercas","descripcion":null}}]}
                """;
        RespuestaLlm r = parser.parsear(json, solicitud);
        assertThat(r.cabecera().get("numeroComprobante").valor()).isEqualTo("0001-00012345");
        assertThat(r.cabecera().get("numeroComprobante").clavesQr()).containsExactly("ptoVta", "nroCmp");
        assertThat(r.cabecera().get("fechaEmision").descripcion()).isEqualTo("Se usó la fecha de emisión.");
        assertThat(r.detalle()).hasSize(2);
        assertThat(r.detalle().get(1).get("descripcion").valor()).isEqualTo("Tuercas");
    }

    @Test
    void campoOmitidoQuedaNoEncontrado() {
        String json = """
                {"cabecera":{"numeroComprobante":{"valor":"1","descripcion":null,"clavesQr":[]}},"detalle":[{}]}
                """;
        RespuestaLlm r = parser.parsear(json, solicitud);
        assertThat(r.cabecera().get("fechaEmision"))
                .isEqualTo(new ValorLlm(null, "Dato no encontrado.", List.of()));
        assertThat(r.detalle().get(0).get("descripcion").valor()).isNull();
        assertThat(r.detalle().get(0).get("descripcion").descripcion()).isEqualTo("Dato no encontrado.");
    }

    @Test
    void campoExtraSeIgnora() {
        String json = """
                {"cabecera":{"numeroComprobante":{"valor":"1"},"fechaEmision":{"valor":"2"},
                 "inventado":{"valor":"x"}},"detalle":[]}
                """;
        RespuestaLlm r = parser.parsear(json, solicitud);
        assertThat(r.cabecera()).containsOnlyKeys("numeroComprobante", "fechaEmision");
    }

    @Test
    void clavesQrInvalidasSeFiltran() {
        String json = """
                {"cabecera":{"numeroComprobante":{"valor":"1","clavesQr":["nroCmp","inventada"]}},"detalle":[]}
                """;
        assertThat(parser.parsear(json, solicitud).cabecera().get("numeroComprobante").clavesQr())
                .containsExactly("nroCmp");
    }

    @Test
    void valorBlankSeTrataComoNoEncontrado() {
        String json = """
                {"cabecera":{"numeroComprobante":{"valor":"  ","descripcion":null}},"detalle":[]}
                """;
        ValorLlm v = parser.parsear(json, solicitud).cabecera().get("numeroComprobante");
        assertThat(v.valor()).isNull();
        assertThat(v.descripcion()).isEqualTo("Dato no encontrado.");
    }

    @Test
    void jsonInvalidoLanzaExcepcionSinContenido() {
        String entrada = "esto no es json SECRETO-123";
        assertThatThrownBy(() -> parser.parsear(entrada, solicitud))
                .isInstanceOf(RespuestaLlmInvalidaException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRETO-123"));
    }
}
