package ar.comprobantes.extractor.sintetico;

import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import java.io.IOException;

/** Nota de Crédito A sin QR: encabezado centrado, tabla simple, totales a la derecha. */
public class DisenoNotaCreditoA implements DisenoComprobante {

    @Override
    public String id() {
        return "nc-a";
    }

    @Override
    public byte[] pdfConTexto(DatosComprobante d) throws IOException {
        try (Lienzo l = new Lienzo()) {
            l.texto(180, 50, "NOTA DE CRÉDITO", 20, true);
            l.rect(285, 58, 26, 26, 1f);
            l.texto(291, 79, "A", 20, true);
            l.texto(40, 110, d.emisorRazonSocial(), 13, true);
            l.texto(40, 126, d.emisorDomicilio(), 9, false);
            l.texto(40, 140, "CUIT Nro: " + Lienzo.cuitConGuiones(d.emisorCuit()), 9, false);
            l.textoDer(555, 110, "Nota de Crédito Nº " + d.numeroImpreso(), 11, true);
            l.textoDer(555, 126, "Emitida el " + Lienzo.fecha(d.fechaEmision()), 10, false);
            l.textoDer(555, 140, "Comprobante asociado: Factura A 0003-00012000", 9, false);

            l.linea(40, 160, 555, 160, 1.5f);
            l.texto(40, 178, "Señor/es: " + d.receptorRazonSocial(), 10, false);
            l.texto(40, 193, "CUIT: " + Lienzo.cuitConGuiones(d.receptorCuit()), 10, false);
            l.linea(40, 203, 555, 203, 0.5f);

            float y = 235;
            l.texto(40, y, "Detalle", 10, true);
            l.textoDer(380, y, "Unidades", 10, true);
            l.textoDer(470, y, "P. Unitario", 10, true);
            l.textoDer(555, y, "Importe", 10, true);
            y += 8;
            l.linea(40, y, 555, y, 0.5f);
            y += 18;
            for (DatosComprobante.Linea li : d.lineas()) {
                l.texto(40, y, li.concepto(), 10, false);
                l.textoDer(380, y, Lienzo.cantidad(li.cantidad()), 10, false);
                l.textoDer(470, y, Lienzo.dinero(li.precioUnitario()), 10, false);
                l.textoDer(555, y, Lienzo.dinero(li.importe()), 10, false);
                y += 20;
            }
            l.linea(40, y - 10, 555, y - 10, 0.5f);
            y += 15;
            l.textoDer(470, y, "Importe Neto Gravado", 10, false);
            l.textoDer(555, y, Lienzo.dinero(d.neto()), 10, false);
            y += 16;
            l.textoDer(470, y, "IVA 21%", 10, false);
            l.textoDer(555, y, Lienzo.dinero(d.iva()), 10, false);
            y += 22;
            l.textoDer(470, y, "Importe Total", 12, true);
            l.textoDer(555, y, Lienzo.dinero(d.totalImpreso()), 12, true);
            l.texto(40, 790, "Documento no válido como factura.", 8, false);
            return l.guardar();
        }
    }

    @Override
    public SolicitudExtraccion solicitud() {
        return DisenoComprobante.solicitudBase("Nota de Crédito Nº", "CUIT Nro", "Emitida el", "Importe Total",
            "Detalle", "Unidades", "Importe");
    }
}
