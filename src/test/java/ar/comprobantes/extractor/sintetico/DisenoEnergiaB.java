package ar.comprobantes.extractor.sintetico;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.api.dto.TipoCampo;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Factura B de servicio eléctrico: datos del suministro, dos vencimientos y conceptos con unidad. */
public class DisenoEnergiaB implements DisenoComprobante {

    @Override
    public String id() {
        return "energia-b";
    }

    @Override
    public byte[] pdfConTexto(DatosComprobante d) throws IOException {
        try (Lienzo l = new Lienzo()) {
            l.relleno(0, 0, Lienzo.ANCHO, 70, 0.9f);
            l.texto(40, 35, d.emisorRazonSocial().toUpperCase(), 17, true);
            l.texto(40, 52, d.emisorDomicilio() + "  -  C.U.I.T. Nro " + Lienzo.cuitConGuiones(d.emisorCuit()), 8, false);
            l.texto(40, 64, "IVA Responsable Inscripto", 8, false);

            l.texto(40, 100, "FACTURA B", 14, true);
            l.texto(40, 118, "Comp. Nro: " + d.numeroImpreso(), 11, false);
            l.texto(40, 134, "Fecha de emisión: " + Lienzo.fecha(d.fechaEmision()), 10, false);
            l.texto(330, 100, "Suministro", 10, true);
            l.texto(330, 118, "Titular: " + d.receptorRazonSocial(), 10, false);
            l.texto(330, 134, "N° de medidor: " + d.numeroMedidor(), 10, false);
            l.texto(330, 150, "Tarifa: T1 Residencial", 10, false);

            l.rect(40, 170, 515, 40, 1f);
            l.texto(50, 188, "1er vto.: " + Lienzo.fecha(d.vencimiento1()), 11, true);
            l.texto(50, 202, "2do vto.: " + Lienzo.fecha(d.vencimiento2()) + " (con recargo)", 9, false);
            l.texto(330, 195, "Total a pagar: $ " + Lienzo.dinero(d.totalImpreso()), 13, true);

            float y = 250;
            l.linea(40, y - 14, 555, y - 14, 1f);
            l.texto(45, y, "Concepto", 9, true);
            l.textoDer(340, y, "Cantidad", 9, true);
            l.texto(350, y, "Unidad", 9, true);
            l.textoDer(550, y, "Importe", 9, true);
            l.linea(40, y + 6, 555, y + 6, 1f);
            y += 24;
            for (DatosComprobante.Linea li : d.lineas()) {
                String unidad = li.concepto().contains("kWh") ? "kWh" : "un.";
                l.texto(45, y, li.concepto(), 10, false);
                l.textoDer(340, y, Lienzo.cantidad(li.cantidad()), 10, false);
                l.texto(350, y, unidad, 10, false);
                l.textoDer(550, y, Lienzo.dinero(li.importe()), 10, false);
                y += 20;
            }
            l.linea(40, y - 8, 555, y - 8, 0.5f);
            y += 12;
            l.textoDer(450, y, "Total a pagar", 11, true);
            l.textoDer(550, y, Lienzo.dinero(d.totalImpreso()), 11, true);

            l.qr(d.qr(), 440, 690, 100);
            l.texto(40, 720, "CAE: " + d.qr().codAut(), 8, false);
            l.texto(40, 733, "Consulte este comprobante en www.afip.gob.ar", 8, false);
            return l.guardar();
        }
    }

    @Override
    public SolicitudExtraccion solicitud() {
        return DisenoComprobante.solicitudBase("Comp. Nro", "C.U.I.T. Nro", "Fecha de emisión", "Total a pagar",
            "Concepto", "Cantidad", "Importe",
            new CampoSolicitado("numeroMedidor", List.of("N° de medidor"), "Número de medidor del suministro.",
                TipoCampo.TEXTO),
            new CampoSolicitado("fechaVencimiento", List.of("1er vto."), "Primer vencimiento.", TipoCampo.FECHA));
    }

    @Override
    public void agregarCabecera(Map<String, Object> cabecera, DatosComprobante d) {
        cabecera.put("numeroMedidor", d.numeroMedidor());
        cabecera.put("fechaVencimiento", d.vencimiento1().format(DateTimeFormatter.ISO_LOCAL_DATE));
    }
}
