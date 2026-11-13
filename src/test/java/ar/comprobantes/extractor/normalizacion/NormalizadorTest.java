package ar.comprobantes.extractor.normalizacion;

import static ar.comprobantes.extractor.api.dto.TipoCampo.CUIT;
import static ar.comprobantes.extractor.api.dto.TipoCampo.FECHA;
import static ar.comprobantes.extractor.api.dto.TipoCampo.IMPORTE;
import static ar.comprobantes.extractor.api.dto.TipoCampo.TEXTO;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NormalizadorTest {

    private final Normalizador n = new Normalizador();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1.234,56|1234.56", "$ 15.000,00|15000.00", "15.000|15000", "1,234.56|1234.56",
        "350|350", "-1.500,5|-1500.5", "(1.500,00)|-1500.00", "12,5|12.5"})
    void normalizaImporte(String crudo, String esperado) {
        ResultadoNormalizacion r = n.normalizar(crudo, IMPORTE);
        assertThat((BigDecimal) r.valor()).isEqualByComparingTo(esperado);
        assertThat(r.observacion()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "02/10/2026|2026-10-02", "2/10/2026|2026-10-02", "02-10-26|2026-10-02",
        "02/10/26|2026-10-02", "02.10.26|2026-10-02", "02.10.2026|2026-10-02",
        "2026-10-02|2026-10-02", "2 de octubre de 2026|2026-10-02"})
    void normalizaFecha(String crudo, String esperado) {
        ResultadoNormalizacion r = n.normalizar(crudo, FECHA);
        assertThat(r.valor()).isEqualTo(esperado);
        assertThat(r.observacion()).isNull();
    }

    @Test
    void cuitConGuionesSeLimpia() {
        ResultadoNormalizacion r = n.normalizar("30-71234567-1", CUIT);
        assertThat(r.valor()).isEqualTo("30712345671");
        assertThat(r.observacion()).isNull();
    }

    @Test
    void cuitInvalidoSeDevuelveConObservacion() {
        ResultadoNormalizacion r = n.normalizar("30-71234567-8", CUIT);
        assertThat(r.valor()).isEqualTo("30712345678");
        assertThat(r.observacion()).isEqualTo("El CUIT no supera la validación del dígito verificador.");
    }

    @Test
    void cuitConLargoIncorrectoDevuelveCrudo() {
        ResultadoNormalizacion r = n.normalizar("123", CUIT);
        assertThat(r.valor()).isEqualTo("123");
        assertThat(r.observacion()).contains("como CUIT");
    }

    @Test
    void fechaIninterpretableDevuelveCrudo() {
        ResultadoNormalizacion r = n.normalizar("fin de mes", FECHA);
        assertThat(r.valor()).isEqualTo("fin de mes");
        assertThat(r.observacion())
            .isEqualTo("No se pudo interpretar 'fin de mes' como FECHA; se devuelve tal cual.");
    }

    @Test
    void fechaInexistenteDevuelveCrudo() {
        assertThat(n.normalizar("31/02/2026", FECHA).valor()).isEqualTo("31/02/2026");
    }

    @Test
    void importeIninterpretableDevuelveCrudo() {
        ResultadoNormalizacion r = n.normalizar("abc", IMPORTE);
        assertThat(r.valor()).isEqualTo("abc");
        assertThat(r.observacion()).contains("como IMPORTE");
    }

    @Test
    void textoColapsaEspacios() {
        assertThat(n.normalizar("  Energía   activa ", TEXTO).valor()).isEqualTo("Energía activa");
    }

    @Test
    void nullDevuelveNull() {
        ResultadoNormalizacion r = n.normalizar(null, IMPORTE);
        assertThat(r.valor()).isNull();
        assertThat(r.observacion()).isNull();
        assertThat(n.normalizar("  ", FECHA).valor()).isNull();
    }
}
