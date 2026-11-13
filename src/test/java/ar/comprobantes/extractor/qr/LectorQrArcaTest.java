package ar.comprobantes.extractor.qr;

import ar.comprobantes.extractor.api.dto.QrArcaResultado;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LectorQrArcaTest {

    private final LectorQrArca lector = new LectorQrArca(new DecodificadorQrArca());

    private static BufferedImage paginaBlanca() {
        BufferedImage img = new BufferedImage(1654, 2339, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.dispose();
        return img;
    }

    private static BufferedImage paginaConQr(String contenido) throws Exception {
        BitMatrix m = new QRCodeWriter().encode(contenido, BarcodeFormat.QR_CODE, 300, 300);
        BufferedImage qr = MatrixToImageWriter.toBufferedImage(m);
        BufferedImage pagina = paginaBlanca();
        Graphics2D g = pagina.createGraphics();
        g.drawImage(qr, 1200, 1900, null);
        g.dispose();
        return pagina;
    }

    @Test
    void encuentraQrEnSegundaPagina() throws Exception {
        QrArcaResultado r = lector.leer(List.of(paginaBlanca(),
                paginaConQr(DecodificadorQrArcaTest.urlQr(DecodificadorQrArcaTest.JSON))));
        assertThat(r.presente()).isTrue();
        assertThat(r.datos().cuit()).isEqualTo(30712345671L);
        assertThat(r.descripcion()).isNull();
    }

    @Test
    void sinQr() {
        QrArcaResultado r = lector.leer(List.of(paginaBlanca()));
        assertThat(r.presente()).isFalse();
        assertThat(r.datos()).isNull();
        assertThat(r.descripcion()).isEqualTo("No se encontró código QR de ARCA.");
    }

    @Test
    void qrQueNoEsDeArca() throws Exception {
        QrArcaResultado r = lector.leer(List.of(paginaConQr("hola")));
        assertThat(r.presente()).isFalse();
        assertThat(r.descripcion()).contains("no corresponde a ARCA");
    }
}
