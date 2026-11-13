package ar.comprobantes.extractor.documento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.api.dto.ModoProcesamiento;
import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.DocumentoDemasiadoGrandeException;
import ar.comprobantes.extractor.error.DocumentoInvalidoException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class ProcesadorDocumentoTest {

    private final ProcesadorDocumento procesador = new ProcesadorDocumento(
        new DetectorTipoDocumento(),
        new ExtractorProperties(null, null, new ExtractorProperties.Documento(10, 150, 200, 2000, 50), null));

    @Test
    void pdfConTextoVaPorModoTexto() throws IOException {
        DocumentoProcesado d = procesador.procesar(pdf(paginaConTexto(), null));

        assertThat(d.tipo()).isEqualTo(TipoDocumento.PDF);
        assertThat(d.modo()).isEqualTo(ModoProcesamiento.TEXTO);
        assertThat(d.paginas()).isEqualTo(1);
        assertThat(d.texto()).contains("Factura N° 0003-00012345").contains("--- Página 1 ---");
        assertThat(d.imagenesLlm()).isEmpty();
        assertThat(d.imagenesQr()).hasSize(1);
        assertThat(d.tiemposMs()).containsKeys("deteccion", "extraccionTexto", "rasterizacion");
    }

    @Test
    void pdfSinTextoVaPorModoImagen() throws IOException {
        DocumentoProcesado d = procesador.procesar(pdf(paginaConImagen()));

        assertThat(d.modo()).isEqualTo(ModoProcesamiento.IMAGEN);
        assertThat(d.texto()).isNull();
        assertThat(d.imagenesLlm()).hasSize(1);
        assertThat(d.imagenesQr()).hasSize(1);
    }

    @Test
    void pdfMixtoVaEnteroPorImagen() throws IOException {
        DocumentoProcesado d = procesador.procesar(pdf(paginaConTexto(), paginaConImagen()));

        assertThat(d.paginas()).isEqualTo(2);
        assertThat(d.modo()).isEqualTo(ModoProcesamiento.IMAGEN);
        assertThat(d.imagenesLlm()).hasSize(2);
        assertThat(d.imagenesQr()).hasSize(2);
    }

    @Test
    void pdfConMasPaginasQueElLimite() throws IOException {
        byte[] contenido;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < 11; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(out);
            contenido = out.toByteArray();
        }
        assertThatThrownBy(() -> procesador.procesar(contenido))
            .isInstanceOf(DocumentoDemasiadoGrandeException.class);
    }

    @Test
    void pdfCorruptoEs400() {
        assertThatThrownBy(() -> procesador.procesar("%PDF-1.7 basura".getBytes()))
            .isInstanceOf(DocumentoInvalidoException.class)
            .hasMessage("El PDF no pudo leerse.");
    }

    @Test
    void pdfConContrasenaEs400() throws IOException {
        byte[] contenido;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy politica =
                new StandardProtectionPolicy("duenio", "usuario", new AccessPermission());
            politica.setEncryptionKeyLength(128);
            doc.protect(politica);
            doc.save(out);
            contenido = out.toByteArray();
        }
        assertThatThrownBy(() -> procesador.procesar(contenido))
            .isInstanceOf(DocumentoInvalidoException.class)
            .hasMessageContaining("protegido con contraseña");
    }

    @Test
    void fotoGrandeSeRedimensiona() throws IOException {
        DocumentoProcesado d = procesador.procesar(png(6000, 4000));

        assertThat(d.tipo()).isEqualTo(TipoDocumento.PNG);
        assertThat(d.modo()).isEqualTo(ModoProcesamiento.IMAGEN);
        assertThat(d.paginas()).isEqualTo(1);
        BufferedImage llm = d.imagenesLlm().get(0);
        assertThat(llm.getWidth()).isEqualTo(2000);
        assertThat(llm.getHeight()).isEqualTo(1333);
        assertThat(d.imagenesQr().get(0).getWidth()).isEqualTo(6000);
    }

    @Test
    void imagenChicaNoSeRedimensiona() throws IOException {
        DocumentoProcesado d = procesador.procesar(png(800, 600));

        assertThat(d.imagenesLlm().get(0).getWidth()).isEqualTo(800);
    }

    @Test
    void aplicaOrientacionExifEnJpeg() throws IOException {
        byte[] jpeg = jpegConOrientacion(300, 200, 6);

        DocumentoProcesado d = procesador.procesar(jpeg);

        assertThat(d.imagenesQr().get(0).getWidth()).isEqualTo(200);
        assertThat(d.imagenesQr().get(0).getHeight()).isEqualTo(300);
    }

    @Test
    void registraTiempos() throws IOException {
        DocumentoProcesado d = procesador.procesar(pdf(paginaConTexto(), null));

        assertThat(d.tiemposMs()).containsKeys("deteccion", "extraccionTexto");
    }

    // --- helpers ---

    private interface Pagina {
        void dibujar(PDDocument doc, PDPage pagina) throws IOException;
    }

    private static Pagina paginaConTexto() {
        return (doc, pagina) -> {
            try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                PDType1Font fuente = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.beginText();
                cs.setFont(fuente, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Factura N° 0003-00012345");
                cs.newLineAtOffset(0, -20);
                cs.showText("Razon social de ejemplo sociedad anonima CUIT 30711111118");
                cs.newLineAtOffset(0, -20);
                cs.showText("Total del comprobante 1234 pesos con cincuenta centavos");
                cs.endText();
            }
        };
    }

    private static Pagina paginaConImagen() {
        return (doc, pagina) -> {
            BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            PDImageXObject x = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                cs.drawImage(x, 50, 500, 200, 200);
            }
        };
    }

    private static byte[] pdf(Pagina... paginas) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Pagina p : paginas) {
                if (p == null) {
                    continue;
                }
                PDPage pagina = new PDPage(PDRectangle.A4);
                doc.addPage(pagina);
                p.dibujar(doc, pagina);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] png(int ancho, int alto) throws IOException {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** JPEG con un segmento APP1 Exif mínimo (little endian) que declara la orientación. */
    private static byte[] jpegConOrientacion(int ancho, int alto, int orientacion) throws IOException {
        ByteArrayOutputStream base = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB), "jpg", base);
        byte[] jpeg = base.toByteArray();
        byte[] exif = {
            'E', 'x', 'i', 'f', 0, 0,
            'I', 'I', 0x2A, 0, 8, 0, 0, 0,
            1, 0,
            0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientacion, 0, 0, 0,
            0, 0, 0, 0};
        int largo = exif.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(largo >> 8);
        out.write(largo & 0xFF);
        out.write(exif);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
