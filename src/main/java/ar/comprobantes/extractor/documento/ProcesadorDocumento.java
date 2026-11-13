package ar.comprobantes.extractor.documento;

import ar.comprobantes.extractor.api.dto.ModoProcesamiento;
import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.DocumentoDemasiadoGrandeException;
import ar.comprobantes.extractor.error.DocumentoInvalidoException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Procesa el documento enteramente en memoria (sin archivos temporales). */
@Component
public class ProcesadorDocumento {

    private final DetectorTipoDocumento detector;
    private final ExtractorProperties.Documento config;

    public ProcesadorDocumento(DetectorTipoDocumento detector, ExtractorProperties propiedades) {
        this.detector = detector;
        this.config = propiedades.documento();
    }

    public DocumentoProcesado procesar(byte[] contenido) {
        Map<String, Long> tiempos = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        TipoDocumento tipo = detector.detectar(contenido);
        tiempos.put("deteccion", transcurridoMs(t0));
        if (tipo == TipoDocumento.PDF) {
            return procesarPdf(contenido, tiempos);
        }
        return procesarImagen(contenido, tipo, tiempos);
    }

    private DocumentoProcesado procesarPdf(byte[] contenido, Map<String, Long> tiempos) {
        try (PDDocument pdf = Loader.loadPDF(contenido, "", null, null, IOUtils.createMemoryOnlyStreamCache())) {
            int paginas = pdf.getNumberOfPages();
            if (paginas > config.maxPaginas()) {
                throw new DocumentoDemasiadoGrandeException(
                    "El PDF tiene " + paginas + " páginas; el máximo permitido es " + config.maxPaginas() + ".");
            }
            if (paginas == 0) {
                throw new DocumentoInvalidoException("El PDF no tiene páginas.");
            }

            long t1 = System.nanoTime();
            StringBuilder texto = new StringBuilder();
            boolean todasUtilizables = true;
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int i = 1; i <= paginas; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String paginaTexto = stripper.getText(pdf);
                if (cantidadAlfanumericos(paginaTexto) < config.umbralCaracteresPorPagina()) {
                    todasUtilizables = false;
                }
                texto.append("\n--- Página ").append(i).append(" ---\n").append(paginaTexto);
            }
            tiempos.put("extraccionTexto", transcurridoMs(t1));

            long t2 = System.nanoTime();
            PDFRenderer renderer = new PDFRenderer(pdf);
            List<BufferedImage> qr = new ArrayList<>();
            List<BufferedImage> llm = new ArrayList<>();
            for (int i = 0; i < paginas; i++) {
                qr.add(renderer.renderImageWithDPI(i, config.dpiQr(), ImageType.RGB));
                if (!todasUtilizables) {
                    llm.add(renderer.renderImageWithDPI(i, config.dpiImagen(), ImageType.RGB));
                }
            }
            tiempos.put("rasterizacion", transcurridoMs(t2));

            ModoProcesamiento modo = todasUtilizables ? ModoProcesamiento.TEXTO : ModoProcesamiento.IMAGEN;
            return new DocumentoProcesado(TipoDocumento.PDF, paginas, modo,
                todasUtilizables ? texto.toString() : null, llm, qr, tiempos);
        } catch (InvalidPasswordException e) {
            throw new DocumentoInvalidoException("El PDF está protegido con contraseña.", e);
        } catch (IOException e) {
            throw new DocumentoInvalidoException("El PDF no pudo leerse.", e);
        }
    }

    private DocumentoProcesado procesarImagen(byte[] contenido, TipoDocumento tipo, Map<String, Long> tiempos) {
        tiempos.put("extraccionTexto", 0L);
        long t0 = System.nanoTime();
        BufferedImage original;
        try {
            original = ImageIO.read(new ByteArrayInputStream(contenido));
        } catch (IOException e) {
            throw new DocumentoInvalidoException("La imagen no pudo leerse.", e);
        }
        if (original == null) {
            throw new DocumentoInvalidoException("La imagen no pudo leerse.");
        }
        if (tipo == TipoDocumento.JPEG) {
            original = aplicarOrientacion(original, orientacionExif(contenido));
        }
        BufferedImage paraLlm = limitarLado(original, config.maxLadoPx());
        tiempos.put("rasterizacion", transcurridoMs(t0));
        return new DocumentoProcesado(tipo, 1, ModoProcesamiento.IMAGEN, null,
            List.of(paraLlm), List.of(original), tiempos);
    }

    static BufferedImage limitarLado(BufferedImage img, int maxLado) {
        int mayor = Math.max(img.getWidth(), img.getHeight());
        if (mayor <= maxLado) {
            return img;
        }
        double escala = (double) maxLado / mayor;
        int w = Math.max(1, (int) Math.round(img.getWidth() * escala));
        int h = Math.max(1, (int) Math.round(img.getHeight() * escala));
        BufferedImage destino = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = destino.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return destino;
    }

    /** Devuelve la orientación EXIF (1-8) o 1 si no existe o no es legible. */
    static int orientacionExif(byte[] jpeg) {
        try {
            int pos = 2;
            while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
                int marcador = jpeg[pos + 1] & 0xFF;
                if (marcador == 0xDA || marcador == 0xD9) {
                    break;
                }
                int largo = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
                if (marcador == 0xE1 && largo >= 16 && esExif(jpeg, pos + 4)) {
                    return leerOrientacionTiff(jpeg, pos + 10, pos + 2 + largo);
                }
                pos += 2 + largo;
            }
        } catch (RuntimeException e) {
            // EXIF ilegible: se deja la imagen sin rotar.
        }
        return 1;
    }

    private static boolean esExif(byte[] b, int off) {
        return b[off] == 'E' && b[off + 1] == 'x' && b[off + 2] == 'i' && b[off + 3] == 'f'
            && b[off + 4] == 0 && b[off + 5] == 0;
    }

    private static int leerOrientacionTiff(byte[] b, int tiff, int fin) {
        boolean little = b[tiff] == 'I';
        int ifd = tiff + (int) entero(b, tiff + 4, 4, little);
        int n = (int) entero(b, ifd, 2, little);
        for (int i = 0; i < n; i++) {
            int e = ifd + 2 + i * 12;
            if (e + 12 > fin) {
                break;
            }
            if (entero(b, e, 2, little) == 0x0112) {
                int v = (int) entero(b, e + 8, 2, little);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static long entero(byte[] b, int off, int bytes, boolean little) {
        long r = 0;
        for (int i = 0; i < bytes; i++) {
            int v = b[off + (little ? bytes - 1 - i : i)] & 0xFF;
            r = (r << 8) | v;
        }
        return r;
    }

    static BufferedImage aplicarOrientacion(BufferedImage img, int orientacion) {
        if (orientacion <= 1) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        boolean intercambia = orientacion >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientacion) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { return img; }
        }
        BufferedImage destino = new BufferedImage(intercambia ? h : w, intercambia ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = destino.createGraphics();
        try {
            g.drawImage(img, t, null);
        } finally {
            g.dispose();
        }
        return destino;
    }

    private static int cantidadAlfanumericos(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private static long transcurridoMs(long desdeNanos) {
        return (System.nanoTime() - desdeNanos) / 1_000_000;
    }
}
