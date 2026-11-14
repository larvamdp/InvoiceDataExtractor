package ar.comprobantes.extractor.sintetico;

import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import java.io.IOException;

/** Factura A de comercio minorista: cabecera en dos cajas, tabla con código y totales discriminados. */
public class DisenoRetailA implements DisenoComprobante {

    @Override
    public String id() {
        return "retail-a";
    }

    @Override
    public byte[] pdfConTexto(DatosComprobante d) throws IOException {
        try (Lienzo l = new Lienzo()) {
            l.rect(30, 30, 535, 110, 1f);
            l.linea(297, 30, 297, 140, 1f);
            l.texto(40, 60, d.emisorRazonSocial(), 11, true);
            l.texto(40, 80, d.emisorDomicilio(), 9, false);
            l.texto(40, 95, "IVA Responsable Inscripto", 9, false);
            l.texto(40, 110, "CUIT: " + Lienzo.cuitConGuiones(d.emisorCuit()), 9, false);
            l.rect(278, 30, 38, 38, 1f);
            l.texto(288, 58, "A", 26, true);
            l.texto(310, 55, "FACTURA", 16, true);
            l.texto(310, 80, "Factura N° " + d.numeroImpreso(), 11, true);
            l.texto(310, 98, "Fecha: " + Lienzo.fecha(d.fechaEmision()), 10, false);
            l.texto(310, 115, "CUIT: " + Lienzo.cuitConGuiones(d.emisorCuit()), 9, false);

            l.rect(30, 150, 535, 45, 0.5f);
            l.texto(40, 168, "Cliente: " + d.receptorRazonSocial(), 10, false);
            l.texto(40, 185, "CUIT: " + Lienzo.cuitConGuiones(d.receptorCuit()) + "   Condición de venta: Contado", 10, false);

            float y = 220;
            l.relleno(30, y - 12, 535, 18, 0.85f);
            l.texto(35, y, "Cód.", 9, true);
            l.texto(80, y, "Descripción", 9, true);
            l.textoDer(380, y, "Cant.", 9, true);
            l.textoDer(470, y, "Precio Unit.", 9, true);
            l.textoDer(560, y, "Importe", 9, true);
            y += 20;
            int i = 1;
            for (DatosComprobante.Linea li : d.lineas()) {
                l.texto(35, y, "%04d".formatted(i * 17), 9, false);
                l.texto(80, y, li.concepto(), 9, false);
                l.textoDer(380, y, Lienzo.cantidad(li.cantidad()), 9, false);
                l.textoDer(470, y, Lienzo.dinero(li.precioUnitario()), 9, false);
                l.textoDer(560, y, Lienzo.dinero(li.importe()), 9, false);
                y += 18;
                i++;
            }
            l.linea(30, y - 8, 565, y - 8, 0.5f);

            y += 15;
            l.textoDer(470, y, "Subtotal:", 10, false);
            l.textoDer(560, y, Lienzo.dinero(d.neto()), 10, false);
            y += 16;
            l.textoDer(470, y, "IVA 21%:", 10, false);
            l.textoDer(560, y, Lienzo.dinero(d.iva()), 10, false);
            y += 22;
            l.textoDer(470, y, "TOTAL", 13, true);
            l.textoDer(560, y, "$ " + Lienzo.dinero(d.totalImpreso()), 13, true);

            l.qr(d.qr(), 40, 700, 95);
            l.texto(150, 725, "CAE N°: " + d.qr().codAut(), 9, false);
            l.texto(150, 740, "Comprobante autorizado", 9, false);
            return l.guardar();
        }
    }

    @Override
    public SolicitudExtraccion solicitud() {
        return DisenoComprobante.solicitudBase("Factura N°", "CUIT", "Fecha", "TOTAL",
            "Descripción", "Cant.", "Importe");
    }
}
