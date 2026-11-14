package ar.comprobantes.extractor.sintetico;

import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import java.io.IOException;

/** Nota de Débito B cuyo QR trae un importe distinto al impreso (discrepancia a propósito). */
public class DisenoNotaDebitoB implements DisenoComprobante {

    @Override
    public String id() {
        return "nd-b";
    }

    @Override
    public byte[] pdfConTexto(DatosComprobante d) throws IOException {
        try (Lienzo l = new Lienzo()) {
            l.relleno(30, 30, 535, 26, 0.8f);
            l.texto(40, 48, "NOTA DE DÉBITO  B", 14, true);
            l.rect(30, 56, 535, 90, 1f);
            l.texto(40, 76, d.emisorRazonSocial(), 13, true);
            l.texto(40, 92, d.emisorDomicilio(), 9, false);
            l.texto(40, 106, "CUIT " + Lienzo.cuitConGuiones(d.emisorCuit()) + " - Resp. Inscripto", 9, false);
            l.texto(330, 76, "ND Nro. " + d.numeroImpreso(), 12, true);
            l.texto(330, 94, "Fecha: " + Lienzo.fecha(d.fechaEmision()), 10, false);
            l.texto(330, 110, "Pto. de venta: " + "%04d".formatted(d.puntoVenta()), 10, false);

            l.texto(30, 175, "Cliente: " + d.receptorRazonSocial(), 10, false);
            l.texto(30, 190, "Consumidor final - DNI " + d.qr().nroDocRec(), 10, false);

            float y = 225;
            l.rect(30, y - 14, 535, 20, 0.7f);
            l.texto(36, y, "Concepto", 9, true);
            l.textoDer(400, y, "Cant", 9, true);
            l.textoDer(560, y, "Monto", 9, true);
            y += 22;
            for (DatosComprobante.Linea li : d.lineas()) {
                l.texto(36, y, li.concepto(), 10, false);
                l.textoDer(400, y, Lienzo.cantidad(li.cantidad()), 10, false);
                l.textoDer(560, y, Lienzo.dinero(li.importe()), 10, false);
                y += 20;
            }
            y += 20;
            l.rect(380, y - 16, 185, 26, 1f);
            l.texto(388, y, "Total Débito", 11, true);
            l.textoDer(558, y, "$ " + Lienzo.dinero(d.totalImpreso()), 11, true);

            l.qr(d.qr(), 40, 690, 90);
            l.texto(145, 710, "CAE " + d.qr().codAut(), 9, false);
            return l.guardar();
        }
    }

    @Override
    public SolicitudExtraccion solicitud() {
        return DisenoComprobante.solicitudBase("ND Nro.", "CUIT", "Fecha:", "Total Débito",
            "Concepto", "Cant", "Monto");
    }
}
