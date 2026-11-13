package ar.comprobantes.extractor.documento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.error.FormatoNoSoportadoException;
import org.junit.jupiter.api.Test;

class DetectorTipoDocumentoTest {

    private final DetectorTipoDocumento detector = new DetectorTipoDocumento();

    @Test
    void detectaPdfJpegPng() {
        assertThat(detector.detectar("%PDF-1.7 resto".getBytes())).isEqualTo(TipoDocumento.PDF);
        assertThat(detector.detectar(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}))
            .isEqualTo(TipoDocumento.JPEG);
        assertThat(detector.detectar(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D}))
            .isEqualTo(TipoDocumento.PNG);
    }

    @Test
    void rechazaTextoPlano() {
        assertThatThrownBy(() -> detector.detectar("hola".getBytes()))
            .isInstanceOf(FormatoNoSoportadoException.class)
            .hasMessage("El documento no es PDF, JPG ni PNG.");
    }

    @Test
    void rechazaVacio() {
        assertThatThrownBy(() -> detector.detectar(new byte[0]))
            .isInstanceOf(FormatoNoSoportadoException.class);
    }
}
