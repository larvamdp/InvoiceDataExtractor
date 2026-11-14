package ar.comprobantes.extractor.sintetico;

import static org.assertj.core.api.Assertions.assertThat;

import ar.comprobantes.extractor.api.dto.ModoProcesamiento;
import ar.comprobantes.extractor.api.dto.QrArcaResultado;
import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.documento.DetectorTipoDocumento;
import ar.comprobantes.extractor.documento.DocumentoProcesado;
import ar.comprobantes.extractor.documento.ProcesadorDocumento;
import ar.comprobantes.extractor.normalizacion.ValidadorCuit;
import ar.comprobantes.extractor.qr.DecodificadorQrArca;
import ar.comprobantes.extractor.qr.LectorQrArca;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneradorComprobantesTest {

    @TempDir
    static Path salida;

    private final ProcesadorDocumento procesador = new ProcesadorDocumento(
        new DetectorTipoDocumento(),
        new ExtractorProperties(null, null, new ExtractorProperties.Documento(10, 150, 200, 2000, 50), null));
    private final LectorQrArca lector = new LectorQrArca(new DecodificadorQrArca());

    @BeforeAll
    static void generar() throws Exception {
        GeneradorComprobantes.main(new String[] {salida.toString()});
    }

    private static Path documento(String id) throws IOException {
        try (Stream<Path> s = Files.list(salida.resolve(id))) {
            return s.filter(p -> p.getFileName().toString().startsWith("documento.")).findFirst().orElseThrow();
        }
    }

    private QrArcaResultado qr(String id) throws IOException {
        DocumentoProcesado d = procesador.procesar(Files.readAllBytes(documento(id)));
        return lector.leer(d.imagenesQr());
    }

    @Test
    void generaDoceDocumentos() throws IOException {
        try (Stream<Path> s = Files.list(salida)) {
            assertThat(s.filter(Files::isDirectory)).hasSize(12);
        }
        for (String dis : List.of("retail-a", "energia-b", "nc-a", "nd-b")) {
            for (String forma : List.of("texto", "escaneo", "foto")) {
                String id = dis + "-" + forma;
                assertThat(documento(id).getFileName().toString())
                    .isEqualTo(forma.equals("foto") ? "documento.jpg" : "documento.pdf");
                assertThat(salida.resolve(id).resolve("solicitud.json")).exists();
                assertThat(salida.resolve(id).resolve("esperado.json")).exists();
            }
        }
    }

    @Test
    void cuitsFicticiosSonValidos() {
        for (String id : GeneradorComprobantes.IDS_DISENO) {
            DatosComprobante d = GeneradorComprobantes.datos(id);
            assertThat(ValidadorCuit.esValido(d.emisorCuit())).as(id).isTrue();
            if (d.receptorCuit() != null) {
                assertThat(ValidadorCuit.esValido(d.receptorCuit())).as(id).isTrue();
            }
        }
    }

    @Test
    void pdfTextoVaPorModoTextoYEscaneoPorImagen() throws IOException {
        assertThat(procesador.procesar(Files.readAllBytes(documento("retail-a-texto"))).modo())
            .isEqualTo(ModoProcesamiento.TEXTO);
        assertThat(procesador.procesar(Files.readAllBytes(documento("retail-a-escaneo"))).modo())
            .isEqualTo(ModoProcesamiento.IMAGEN);
    }

    @Test
    void qrLegibleEnLasTresFormas() throws IOException {
        for (String forma : List.of("texto", "escaneo", "foto")) {
            QrArcaResultado r = qr("retail-a-" + forma);
            assertThat(r.presente()).as("retail-a-" + forma).isTrue();
            assertThat(r.datos().cuit())
                .isEqualTo(Long.parseLong(GeneradorComprobantes.datos("retail-a").emisorCuit()));
            assertThat(qr("nc-a-" + forma).presente()).as("nc-a-" + forma).isFalse();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ndbTieneDiscrepancia() {
        DatosComprobante d = GeneradorComprobantes.datos("nd-b");
        assertThat(d.qr().importe()).isNotEqualByComparingTo(d.totalImpreso());
        Map<String, Object> esperado = new DisenoNotaDebitoB().esperado(d);
        Map<String, Object> cabecera = (Map<String, Object>) esperado.get("cabecera");
        assertThat((BigDecimal) cabecera.get("importeTotal")).isEqualByComparingTo(d.qr().importe());
    }
}
