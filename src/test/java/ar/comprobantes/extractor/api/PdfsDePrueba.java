package ar.comprobantes.extractor.api;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

final class PdfsDePrueba {

    private PdfsDePrueba() {
    }

    static byte[] pdf(String... lineas) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage pagina = new PDPage(PDRectangle.A4);
            doc.addPage(pagina);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 750);
                for (String l : List.of(lineas)) {
                    cs.showText(l);
                    cs.newLineAtOffset(0, -20);
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
