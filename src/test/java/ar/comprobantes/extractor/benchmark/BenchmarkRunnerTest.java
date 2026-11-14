package ar.comprobantes.extractor.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BenchmarkRunnerTest {

    @Test
    void errorNoMuestraPorcentajesMedidos() {
        var f = new BenchmarkRunner.Fila("d", "texto", "-", "-", 504, 600000, 0, 0, 0, 0, 0, 0, false);
        assertThat(BenchmarkRunner.lineaResumen("ERROR", List.of(f)))
                .contains("aciertos=n/d", "inventados=n/d", "<=60s=n/d")
                .doesNotContain("0.0%");
    }

    @Test
    void modoMedidoMuestraPorcentajes() {
        var f = new BenchmarkRunner.Fila("d", "texto", "TEXTO", "false", 200, 30000, 20000, 0, 8, 10, 1, 1, true);
        assertThat(BenchmarkRunner.lineaResumen("TEXTO", List.of(f)))
                .contains("aciertos=80.0%", "inventados=1", "<=60s=100%");
    }
}
