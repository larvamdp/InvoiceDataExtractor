package ar.comprobantes.extractor.conciliacion;

import static org.assertj.core.api.Assertions.assertThat;

import ar.comprobantes.extractor.api.dto.TipoCampo;
import ar.comprobantes.extractor.normalizacion.Normalizador;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class VerificadorTextualTest {

    private final VerificadorTextual verificador = new VerificadorTextual(new Normalizador());

    @Test void importeConDistintoFormato() {
        assertThat(verificador.aparece("15000", new BigDecimal("15000"), TipoCampo.IMPORTE,
            "Subtotal 1.000,00\nTOTAL $ 15.000,00")).isTrue();
    }

    @Test void fechaConDistintoFormato() {
        assertThat(verificador.aparece("2026-10-02", "2026-10-02", TipoCampo.FECHA, "Fecha: 02/10/26")).isTrue();
    }

    @Test void textoInventado() {
        assertThat(verificador.aparece("Energía reactiva", "Energía reactiva", TipoCampo.TEXTO,
            "Energía activa 100 kWh")).isFalse();
    }

    @Test void textoPresenteSinDistinguirMayusculas() {
        assertThat(verificador.aparece("energía  activa", "energía activa", TipoCampo.TEXTO,
            "ENERGÍA ACTIVA 100 kWh")).isTrue();
    }

    @Test void cuitConGuiones() {
        assertThat(verificador.aparece("30712345671", "30712345671", TipoCampo.CUIT, "CUIT: 30-71234567-1")).isTrue();
    }

    @Test void importeAusente() {
        assertThat(verificador.aparece("99", new BigDecimal("99"), TipoCampo.IMPORTE, "TOTAL $ 15.000,00")).isFalse();
    }
}
