package ar.comprobantes.extractor.sintetico;

import ar.comprobantes.extractor.qr.DatosQrArca;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

/** Utilidades de dibujo sobre una página A4 (coordenadas con origen arriba-izquierda, en puntos). */
final class Lienzo implements AutoCloseable {

    static final float ANCHO = 595f;
    static final float ALTO = 842f;

    private static final DecimalFormat DINERO = new DecimalFormat("#,##0.00", new DecimalFormatSymbols(new Locale("es", "AR")));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PDDocument doc = new PDDocument();
    private boolean cerrado;
    private final PDPageContentStream cs;
    private final PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont negrita = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    Lienzo() throws IOException {
        doc.setDocumentId(1L); // ID fijo: salida determinista
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        cs = new PDPageContentStream(doc, page);
    }

    void texto(float x, float y, String s, float tam, boolean bold) throws IOException {
        cs.beginText();
        cs.setFont(bold ? negrita : normal, tam);
        cs.newLineAtOffset(x, ALTO - y);
        cs.showText(s);
        cs.endText();
    }

    void textoDer(float xDer, float y, String s, float tam, boolean bold) throws IOException {
        float w = (bold ? negrita : normal).getStringWidth(s) / 1000f * tam;
        texto(xDer - w, y, s, tam, bold);
    }

    void linea(float x1, float y1, float x2, float y2, float grosor) throws IOException {
        cs.setLineWidth(grosor);
        cs.moveTo(x1, ALTO - y1);
        cs.lineTo(x2, ALTO - y2);
        cs.stroke();
    }

    void rect(float x, float y, float w, float h, float grosor) throws IOException {
        cs.setLineWidth(grosor);
        cs.addRect(x, ALTO - y - h, w, h);
        cs.stroke();
    }

    void relleno(float x, float y, float w, float h, float gris) throws IOException {
        cs.setNonStrokingColor(gris);
        cs.addRect(x, ALTO - y - h, w, h);
        cs.fill();
        cs.setNonStrokingColor(0f);
    }

    void imagen(BufferedImage img, float x, float y, float w, float h) throws IOException {
        cs.drawImage(LosslessFactory.createFromImage(doc, img), x, ALTO - y - h, w, h);
    }

    void qr(DatosQrArca datos, float x, float y, float lado) throws IOException {
        try {
            var matriz = new QRCodeWriter().encode(urlQr(datos), BarcodeFormat.QR_CODE, 420, 420);
            imagen(MatrixToImageWriter.toBufferedImage(matriz), x, y, lado, lado);
        } catch (com.google.zxing.WriterException e) {
            throw new IOException(e);
        }
    }

    byte[] guardar() throws IOException {
        cs.close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        doc.close();
        cerrado = true;
        return out.toByteArray();
    }

    @Override
    public void close() throws IOException {
        if (cerrado) {
            return;
        }
        try {
            cs.close();
        } finally {
            doc.close();
        }
    }

    static String urlQr(DatosQrArca datos) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        for (String clave : DatosQrArca.CLAVES) {
            json.put(clave, datos.valorDeClave(clave));
        }
        String b64 = Base64.getEncoder().encodeToString(MAPPER.writeValueAsString(json).getBytes(StandardCharsets.UTF_8));
        return "https://www.afip.gob.ar/fe/qr/?p=" + b64;
    }

    static String dinero(BigDecimal v) {
        return DINERO.format(v.setScale(2, RoundingMode.HALF_UP));
    }

    static String fecha(LocalDate f) {
        return f.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    static String cuitConGuiones(String c) {
        return c.substring(0, 2) + "-" + c.substring(2, 10) + "-" + c.substring(10);
    }

    static String cantidad(BigDecimal c) {
        return c.stripTrailingZeros().scale() <= 0 ? c.toBigInteger().toString()
            : c.toPlainString().replace('.', ',');
    }
}
