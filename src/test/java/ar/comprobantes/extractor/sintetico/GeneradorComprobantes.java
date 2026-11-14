package ar.comprobantes.extractor.sintetico;

import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.qr.DatosQrArca;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Genera el dataset sintético (datos ficticios) en el directorio dado: por diseño, tres documentos
 * (texto, escaneo, foto) con solicitud.json y esperado.json. Determinista.
 */
public final class GeneradorComprobantes {

    public static final List<String> IDS_DISENO = List.of("retail-a", "energia-b", "nc-a", "nd-b");
    private static final int DPI = 200;
    private static final float CALIDAD_JPEG = 0.8f;

    private static final ObjectMapper MAPPER_SOLICITUD = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT).setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final ObjectMapper MAPPER_ESPERADO = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private GeneradorComprobantes() {
    }

    public static void main(String[] args) throws Exception {
        Path salida = Path.of(args[0]);
        for (DisenoComprobante diseno : disenos()) {
            DatosComprobante d = datos(diseno.id());
            byte[] pdf = diseno.pdfConTexto(d);
            BufferedImage pagina = renderizar(pdf, ImageType.RGB);

            escribir(salida, diseno, d, "texto", "documento.pdf", pdf);
            escribir(salida, diseno, d, "escaneo", "documento.pdf", pdfSoloImagen(Degradador.escaneo(pagina)));
            escribir(salida, diseno, d, "foto", "documento.jpg", Degradador.jpeg(Degradador.foto(pagina), CALIDAD_JPEG));
        }
    }

    static List<DisenoComprobante> disenos() {
        return List.of(new DisenoRetailA(), new DisenoEnergiaB(), new DisenoNotaCreditoA(), new DisenoNotaDebitoB());
    }

    public static DatosComprobante datos(String idDiseno) {
        return switch (idDiseno) {
            case "retail-a" -> retailA();
            case "energia-b" -> energiaB();
            case "nc-a" -> notaCreditoA();
            case "nd-b" -> notaDebitoB();
            default -> throw new IllegalArgumentException("Diseño desconocido: " + idDiseno);
        };
    }

    // ---- datos ficticios -------------------------------------------------------------------------

    private static DatosComprobante retailA() {
        String[][] items = {
            {"Resma papel A4 75g", "10", "4850.00"}, {"Lapicera azul caja x50", "3", "7200.50"},
            {"Carpeta oficio 3 anillos", "12", "2100.00"}, {"Cuaderno tapa dura 100 hojas", "20", "1890.75"},
            {"Marcador permanente negro", "24", "640.00"}, {"Cinta adhesiva 48 mm", "15", "980.00"},
            {"Abrochadora metálica", "4", "5300.00"}, {"Toner genérico negro", "2", "38900.00"}};
        List<DatosComprobante.Linea> lineas = lineas(items);
        BigDecimal neto = suma(lineas);
        BigDecimal iva = neto.multiply(new BigDecimal("0.21")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = neto.add(iva);
        String cuitE = cuit("3071234500");
        String cuitR = cuit("3365432100");
        DatosQrArca qr = new DatosQrArca(1, "2025-03-15", Long.parseLong(cuitE), 3, 1, 12345L, total, "PES",
            BigDecimal.ONE, 80, Long.parseLong(cuitR), "E", 75123456789012L);
        return new DatosComprobante("Distribuidora Ficticia del Plata S.R.L.", cuitE, "Av. Inventada 1234, CABA",
            "Papelera Modelo S.A.", cuitR, "Factura", 1, 3, 12345L, LocalDate.of(2025, 3, 15), null, null, null,
            lineas, neto, iva, total, qr, total);
    }

    private static DatosComprobante energiaB() {
        String[][] items = {
            {"Cargo fijo", "1", "2150.40"}, {"Energía activa 350 kWh", "350", "98.2500"},
            {"Alumbrado público", "1", "1320.00"}};
        List<DatosComprobante.Linea> lineas = lineas(items);
        BigDecimal total = suma(lineas);
        String cuitE = cuit("3066778800");
        DatosQrArca qr = new DatosQrArca(1, "2025-04-02", Long.parseLong(cuitE), 7, 6, 45821L, total, "PES",
            BigDecimal.ONE, 96, 20456789L, "E", 75987654321098L);
        return new DatosComprobante("Cooperativa Eléctrica Ficticia Ltda.", cuitE, "Calle Falsa 742, Rosario",
            "Juan Pérez Ejemplo", null, "Factura", 6, 7, 45821L, LocalDate.of(2025, 4, 2),
            LocalDate.of(2025, 4, 18), LocalDate.of(2025, 4, 30), "00123456", lineas, total, BigDecimal.ZERO, total, qr,
            total);
    }

    private static DatosComprobante notaCreditoA() {
        String[][] items = {
            {"Devolución resma papel A4 75g", "2", "4850.00"}, {"Bonificación por pronto pago", "1", "3500.00"},
            {"Diferencia de precio carpetas", "12", "150.00"}};
        List<DatosComprobante.Linea> lineas = lineas(items);
        BigDecimal neto = suma(lineas);
        BigDecimal iva = neto.multiply(new BigDecimal("0.21")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = neto.add(iva);
        return new DatosComprobante("Distribuidora Ficticia del Plata S.R.L.", cuit("3071234500"),
            "Av. Inventada 1234, CABA", "Papelera Modelo S.A.", cuit("3365432100"), "Nota de Crédito", 3, 3, 678L,
            LocalDate.of(2025, 3, 28), null, null, null, lineas, neto, iva, total, null, total);
    }

    private static DatosComprobante notaDebitoB() {
        String[][] items = {{"Intereses por mora", "1", "8450.00"}, {"Gastos administrativos", "1", "3900.00"}};
        List<DatosComprobante.Linea> lineas = lineas(items);
        BigDecimal impreso = suma(lineas); // 12350,00
        BigDecimal enQr = new BigDecimal("12530.00"); // transposición de dígitos a propósito
        String cuitE = cuit("3055667700");
        DatosQrArca qr = new DatosQrArca(1, "2025-05-09", Long.parseLong(cuitE), 2, 7, 9031L, enQr, "PES",
            BigDecimal.ONE, 96, 30123456L, "E", 76111222333444L);
        return new DatosComprobante("Servicios Imaginarios Cuyo S.A.", cuitE, "Ruta 40 Km 12, Mendoza",
            "María Gómez Muestra", null, "Nota de Débito", 7, 2, 9031L, LocalDate.of(2025, 5, 9), null, null, null,
            lineas, impreso, BigDecimal.ZERO, enQr, qr, impreso);
    }

    private static List<DatosComprobante.Linea> lineas(String[][] items) {
        List<DatosComprobante.Linea> res = new ArrayList<>();
        for (String[] it : items) {
            BigDecimal cant = new BigDecimal(it[1]);
            BigDecimal precio = new BigDecimal(it[2]);
            res.add(new DatosComprobante.Linea(it[0], cant, precio.setScale(2, RoundingMode.HALF_UP),
                cant.multiply(precio).setScale(2, RoundingMode.HALF_UP)));
        }
        return res;
    }

    private static BigDecimal suma(List<DatosComprobante.Linea> lineas) {
        return lineas.stream().map(DatosComprobante.Linea::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Completa 10 dígitos con un dígito verificador válido (avanza el prefijo si el DV sería 10). */
    static String cuit(String diezDigitos) {
        long base = Long.parseLong(diezDigitos);
        int[] pesos = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        while (true) {
            String s = Long.toString(base);
            int suma = 0;
            for (int i = 0; i < 10; i++) {
                suma += (s.charAt(i) - '0') * pesos[i];
            }
            int dv = 11 - (suma % 11);
            if (dv != 10) {
                return s + (dv == 11 ? 0 : dv);
            }
            base++;
        }
    }

    // ---- salida ----------------------------------------------------------------------------------

    private static void escribir(Path salida, DisenoComprobante diseno, DatosComprobante d, String forma,
                                 String nombreDoc, byte[] contenido) throws IOException {
        Path dir = salida.resolve(diseno.id() + "-" + forma);
        Files.createDirectories(dir);
        Files.write(dir.resolve(nombreDoc), contenido);
        SolicitudExtraccion solicitud = diseno.solicitud();
        Map<String, Object> esperado = diseno.esperado(d);
        Files.writeString(dir.resolve("solicitud.json"), MAPPER_SOLICITUD.writeValueAsString(solicitud) + "\n");
        Files.writeString(dir.resolve("esperado.json"), MAPPER_ESPERADO.writeValueAsString(esperado) + "\n");
    }

    private static BufferedImage renderizar(byte[] pdf, ImageType tipo) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFRenderer(doc).renderImageWithDPI(0, DPI, tipo);
        }
    }

    /** PDF de una página A4 con una única imagen JPEG (sin texto): simula un escaneo. */
    private static byte[] pdfSoloImagen(BufferedImage img) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.setDocumentId(1L); // ID fijo: salida determinista
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject x = JPEGFactory.createFromImage(doc, img, CALIDAD_JPEG);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(x, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
