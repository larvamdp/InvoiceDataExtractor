package ar.comprobantes.extractor.sintetico;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Degrada una página renderizada para simular escaneos y fotos. Determinista (semilla fija). */
public final class Degradador {

    private Degradador() {
    }

    /** Rotación de 1 grado, ruido gaussiano leve y escala de grises. */
    public static BufferedImage escaneo(BufferedImage origen) {
        Random rnd = new Random(42);
        BufferedImage rotada = rotar(origen, 1.0, Color.WHITE, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < rotada.getHeight(); y++) {
            for (int x = 0; x < rotada.getWidth(); x++) {
                int v = rotada.getRaster().getSample(x, y, 0);
                int n = (int) Math.round(v + rnd.nextGaussian() * 5);
                rotada.getRaster().setSample(x, y, 0, Math.max(0, Math.min(255, n)));
            }
        }
        return rotada;
    }

    /** Escaneo con trapecio leve (perspectiva), gradiente de brillo y fondo gris; color. */
    public static BufferedImage foto(BufferedImage origen) {
        Random rnd = new Random(7);
        int w = origen.getWidth();
        int h = origen.getHeight();
        BufferedImage rotada = rotar(origen, -1.5, new Color(190, 185, 175), BufferedImage.TYPE_INT_RGB);
        BufferedImage res = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double k = 0.04; // la parte superior queda 4% más angosta que la inferior
        for (int y = 0; y < h; y++) {
            double escala = 1.0 - k * (1.0 - (double) y / h);
            for (int x = 0; x < w; x++) {
                double sx = w / 2.0 + (x - w / 2.0) / escala;
                int rgb = (sx < 0 || sx >= w - 1) ? 0xBEB9AF : muestra(rotada, sx, y);
                double brillo = 0.88 + 0.22 * x / w + rnd.nextGaussian() * 0.01;
                int r = clamp(((rgb >> 16) & 255) * brillo);
                int g = clamp(((rgb >> 8) & 255) * brillo);
                int b = clamp((rgb & 255) * brillo);
                res.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return res;
    }

    /** Codifica como JPEG con la calidad dada (0.8 por defecto en el generador). */
    public static byte[] jpeg(BufferedImage img, float calidad) throws IOException {
        BufferedImage opaca = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB && img.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            opaca = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = opaca.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(calidad);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(opaca, null, null), p);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static BufferedImage rotar(BufferedImage src, double grados, Color fondo, int tipo) {
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), tipo);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(fondo);
        g.fillRect(0, 0, dst.getWidth(), dst.getHeight());
        g.drawImage(src, AffineTransform.getRotateInstance(Math.toRadians(grados),
            src.getWidth() / 2.0, src.getHeight() / 2.0), null);
        g.dispose();
        return dst;
    }

    private static int muestra(BufferedImage img, double x, int y) {
        int x0 = (int) x;
        double f = x - x0;
        int a = img.getRGB(x0, y);
        int b = img.getRGB(x0 + 1, y);
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return (r << 16) | (g << 8) | bl;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
