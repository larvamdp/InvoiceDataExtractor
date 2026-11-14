package ar.comprobantes.extractor.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparadorTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return OM.readTree(s);
    }

    @Test
    void cuentaAciertos() throws Exception {
        Map<String, Object> esperado = Map.of("cabecera", Map.of("importeTotal", 15000));
        var r = Comparador.comparar(esperado,
                json("{\"cabecera\":{\"importeTotal\":{\"valor\":15000.00}}}"));
        assertThat(r.aciertos()).isEqualTo(1);
        assertThat(r.camposTotales()).isEqualTo(1);
        assertThat(r.errores()).isEmpty();
    }

    @Test
    void detectaInventado() throws Exception {
        Map<String, Object> cab = new HashMap<>();
        cab.put("numeroOrdenCompra", null);
        Map<String, Object> esperado = Map.of("cabecera", cab);
        var r = Comparador.comparar(esperado,
                json("{\"cabecera\":{\"numeroOrdenCompra\":{\"valor\":\"X\"}}}"));
        assertThat(r.inventados()).isEqualTo(1);
        assertThat(r.aciertos()).isZero();
    }

    @Test
    void detectaOmitido() throws Exception {
        Map<String, Object> esperado = Map.of("cabecera", Map.of("cuitEmisor", "30712345000"));
        var r = Comparador.comparar(esperado,
                json("{\"cabecera\":{\"cuitEmisor\":{\"valor\":null}}}"));
        assertThat(r.omitidos()).isEqualTo(1);
    }

    @Test
    void comparaDetallePorFila() throws Exception {
        Map<String, Object> esperado = Map.of("detalle", List.of(
                Map.of("concepto", "A", "importe", 10),
                Map.of("concepto", "B", "importe", 20)));
        var r = Comparador.comparar(esperado, json(
                "{\"detalle\":[{\"concepto\":{\"valor\":\"A\"},\"importe\":{\"valor\":10}}]}"));
        assertThat(r.aciertos()).isEqualTo(2);
        assertThat(r.omitidos()).isEqualTo(2);
        assertThat(r.camposTotales()).isEqualTo(4);
    }

    @Test
    void filaSobranteCuentaComoInventada() throws Exception {
        Map<String, Object> esperado = Map.of("detalle", List.of(Map.of("concepto", "A")));
        var r = Comparador.comparar(esperado, json(
                "{\"detalle\":[{\"concepto\":{\"valor\":\"A\"}},{\"concepto\":{\"valor\":\"Z\"}}]}"));
        assertThat(r.inventados()).isEqualTo(1);
    }

    @Test
    void textoIgnoraMayusculasYEspacios() throws Exception {
        Map<String, Object> esperado = Map.of("cabecera", Map.of("numeroComprobante", "Resma  Papel A4"));
        var r = Comparador.comparar(esperado,
                json("{\"cabecera\":{\"numeroComprobante\":{\"valor\":\"  resma papel a4 \"}}}"));
        assertThat(r.aciertos()).isEqualTo(1);
    }
}
