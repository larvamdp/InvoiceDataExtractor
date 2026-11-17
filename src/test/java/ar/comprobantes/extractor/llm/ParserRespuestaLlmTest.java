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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParserRespuestaLlmTest {

    private static final ValorLlm NO_ENCONTRADO = new ValorLlm(null, "Dato no encontrado.", List.of());

    private final ParserRespuestaLlm parser = new ParserRespuestaLlm(new ObjectMapper());

    private static CampoSolicitado campo(String n) {
        return new CampoSolicitado(n, List.of(), null, TipoCampo.TEXTO);
    }

    private final SolicitudExtraccion solicitud = new SolicitudExtraccion(
            List.of(campo("numeroComprobante"), campo("fechaEmision")), List.of(campo("descripcion")));

    private ValorLlm numero(String json) {
        return parser.parsear(json, solicitud).cabecera().get("numeroComprobante");
    }

    @Test
    void parseaRespuestaCompleta() {
        String json = """
                {"cabecera":{"numeroComprobante":"0003-00012345","fechaEmision":"01/05/2024"},
                 "detalle":[{"descripcion":"Tornillos"},{"descripcion":"Tuercas"}],
                 "clavesQr":{"numeroComprobante":["ptoVta","nroCmp"]}}
                """;
        RespuestaLlm r = parser.parsear(json, solicitud);
        assertThat(r.cabecera().get("numeroComprobante"))
                .isEqualTo(new ValorLlm("0003-00012345", null, List.of("ptoVta", "nroCmp")));
        assertThat(r.cabecera().get("fechaEmision")).isEqualTo(new ValorLlm("01/05/2024", null, List.of()));
        assertThat(r.detalle()).hasSize(2);
        assertThat(r.detalle().get(1).get("descripcion")).isEqualTo(new ValorLlm("Tuercas", null, List.of()));
    }

    @Test
    void campoOmitidoQuedaNoEncontrado() {
        String json = """
                {"cabecera":{"numeroComprobante":"1"},"detalle":[{}]}
                """;
        RespuestaLlm r = parser.parsear(json, solicitud);
        assertThat(r.cabecera().get("fechaEmision")).isEqualTo(NO_ENCONTRADO);
        assertThat(r.detalle().get(0).get("descripcion")).isEqualTo(NO_ENCONTRADO);
    }

    @Test
    void campoNullQuedaNoEncontrado() {
        assertThat(numero("{\"cabecera\":{\"numeroComprobante\":null},\"detalle\":[]}")).isEqualTo(NO_ENCONTRADO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"  \"", "\"\"", "\"null\"", "\"NULL\"", "\" Null \""})
    void valorVacioOLiteralNullQuedaNoEncontrado(String valor) {
        assertThat(numero("{\"cabecera\":{\"numeroComprobante\":" + valor + "},\"detalle\":[]}"))
                .isEqualTo(NO_ENCONTRADO);
    }

    @Test
    void valorNumericoSeAceptaComoTexto() {
        assertThat(numero("{\"cabecera\":{\"numeroComprobante\":12100.5},\"detalle\":[]}").valor())
                .isEqualTo("12100.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"valor\":\"1\"}", "[\"1\"]", "true"})
    void valorNoTextualQuedaNoEncontrado(String valor) {
        assertThat(numero("{\"cabecera\":{\"numeroComprobante\":" + valor + "},\"detalle\":[]}"))
                .isEqualTo(NO_ENCONTRADO);
    }

    @Test
    void campoExtraSeIgnora() {
        String json = """
                {"cabecera":{"numeroComprobante":"1","fechaEmision":"2","inventado":"x"},"detalle":[]}
                """;
        assertThat(parser.parsear(json, solicitud).cabecera()).containsOnlyKeys("numeroComprobante", "fechaEmision");
    }

    @Test
    void clavesQrInvalidasSeFiltran() {
        String json = """
                {"cabecera":{"numeroComprobante":"1","fechaEmision":"2"},"detalle":[{"descripcion":"x"}],
                 "clavesQr":{"numeroComprobante":["nroCmp","inventada","nroCmp",7],
                             "fechaEmision":"fecha","descripcion":["importe"],"otro":["importe"]}}
                """;
        RespuestaLlm r = parser.parsear(json, solicitud);
        assertThat(r.cabecera().get("numeroComprobante").clavesQr()).containsExactly("nroCmp");
        assertThat(r.cabecera().get("fechaEmision").clavesQr()).isEmpty();
        assertThat(r.detalle().get(0).get("descripcion").clavesQr()).isEmpty();
    }

    @Test
    void clavesQrNoObjetoSeIgnora() {
        String json = """
                {"cabecera":{"numeroComprobante":"1"},"detalle":[],"clavesQr":["nroCmp"]}
                """;
        assertThat(numero(json).clavesQr()).isEmpty();
    }

    @Test
    void jsonInvalidoLanzaExcepcionSinContenido() {
        String entrada = "esto no es json SECRETO-123";
        assertThatThrownBy(() -> parser.parsear(entrada, solicitud))
                .isInstanceOf(RespuestaLlmInvalidaException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRETO-123"));
    }
}
