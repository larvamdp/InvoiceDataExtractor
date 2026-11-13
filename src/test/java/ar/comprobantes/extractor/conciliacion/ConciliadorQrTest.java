package ar.comprobantes.extractor.conciliacion;

import static org.assertj.core.api.Assertions.assertThat;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.Fuente;
import ar.comprobantes.extractor.api.dto.TipoCampo;
import ar.comprobantes.extractor.api.dto.ValorCampo;
import ar.comprobantes.extractor.llm.ValorLlm;
import ar.comprobantes.extractor.normalizacion.Normalizador;
import ar.comprobantes.extractor.qr.DatosQrArca;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConciliadorQrTest {

    private final ConciliadorQr conciliador = new ConciliadorQr(new Normalizador());
    private final DatosQrArca qr = new DatosQrArca(1, "2026-10-02", 30712345671L, 3, 1, 12345L,
        new BigDecimal("15000"), "PES", BigDecimal.ONE, 80, 20123456786L, "E", 70417054367476L);

    private static CampoSolicitado campo(String nombre, TipoCampo tipo) {
        return new CampoSolicitado(nombre, List.of(), null, tipo);
    }

    @Test void sinClavesQrUsaDocumento() {
        ValorCampo r = conciliador.conciliar(campo("importeTotal", TipoCampo.IMPORTE),
            new ValorLlm("15.000,00", "Total.", List.of()), qr);
        assertThat((BigDecimal) r.valor()).isEqualByComparingTo("15000.00");
        assertThat(r.fuente()).isEqualTo(Fuente.DOCUMENTO);
        assertThat(r.descripcion()).isEqualTo("Total.");
    }

    @Test void unaClaveIgualUsaQrSinDiscrepancia() {
        ValorCampo r = conciliador.conciliar(campo("importeTotal", TipoCampo.IMPORTE),
            new ValorLlm("15.000,00", "Total.", List.of("importe")), qr);
        assertThat((BigDecimal) r.valor()).isEqualByComparingTo("15000.00");
        assertThat(r.fuente()).isEqualTo(Fuente.QR_ARCA);
        assertThat(r.descripcion()).isEqualTo("Total.").doesNotContain("se usó el QR");
    }

    @Test void unaClaveDistintaInformaDiscrepancia() {
        ValorCampo r = conciliador.conciliar(campo("importeTotal", TipoCampo.IMPORTE),
            new ValorLlm("14.000,00", "Total.", List.of("importe")), qr);
        assertThat(((BigDecimal) r.valor()).doubleValue()).isEqualTo(15000.0);
        assertThat(r.fuente()).isEqualTo(Fuente.QR_ARCA);
        assertThat(r.descripcion()).contains("En el documento figura 14.000,00; el QR indica 15000");
    }

    @Test void cuitDelQrComoString() {
        ValorCampo r = conciliador.conciliar(campo("cuitEmisor", TipoCampo.CUIT),
            new ValorLlm("30-71234567-1", null, List.of("cuit")), qr);
        assertThat(r.valor()).isEqualTo("30712345671");
        assertThat(r.fuente()).isEqualTo(Fuente.QR_ARCA);
    }

    @Test void numeroCompuestoVerificado() {
        ValorCampo r = conciliador.conciliar(campo("numeroFactura", TipoCampo.TEXTO),
            new ValorLlm("A 0003-00012345", "Número.", List.of("ptoVta", "nroCmp")), qr);
        assertThat(r.valor()).isEqualTo("A 0003-00012345");
        assertThat(r.fuente()).isEqualTo(Fuente.DOCUMENTO);
        assertThat(r.descripcion()).contains("Verificado con QR de ARCA.");
    }

    @Test void numeroCompuestoNoCoincide() {
        ValorCampo r = conciliador.conciliar(campo("numeroFactura", TipoCampo.TEXTO),
            new ValorLlm("0003-00012346", "Número.", List.of("ptoVta", "nroCmp")), qr);
        assertThat(r.fuente()).isEqualTo(Fuente.DOCUMENTO);
        assertThat(r.descripcion()).contains("No coincide con el QR de ARCA (ptoVta=3, nroCmp=12345); verificar.");
    }

    @Test void soloClaveCodigoSinVerificacion() {
        ValorCampo r = conciliador.conciliar(campo("tipoComprobante", TipoCampo.TEXTO),
            new ValorLlm("Factura A", "Tipo.", List.of("tipoCmp")), qr);
        assertThat(r.valor()).isEqualTo("Factura A");
        assertThat(r.fuente()).isEqualTo(Fuente.DOCUMENTO);
        assertThat(r.descripcion()).doesNotContain("Verificado").doesNotContain("No coincide");
    }

    @Test void valorNullSinQrQuedaNoEncontrado() {
        ValorCampo r = conciliador.conciliar(campo("x", TipoCampo.TEXTO),
            new ValorLlm(null, "Dato no encontrado.", List.of()), null);
        assertThat(r.valor()).isNull();
        assertThat(r.fuente()).isNull();
        assertThat(r.descripcion()).isEqualTo("Dato no encontrado.");
    }

    @Test void observacionDeNormalizacionSeAgrega() {
        ValorCampo r = conciliador.conciliar(campo("c", TipoCampo.CUIT),
            new ValorLlm("30-71234567-2", "CUIT.", List.of()), null);
        assertThat(r.descripcion()).startsWith("CUIT. ").contains("dígito verificador");
    }
}
