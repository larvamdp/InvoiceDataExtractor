package ar.comprobantes.extractor.sintetico;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.api.dto.TipoCampo;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface DisenoComprobante {

    String id();

    byte[] pdfConTexto(DatosComprobante d) throws IOException;

    SolicitudExtraccion solicitud();

    /** Forma {cabecera:{nombre:valorNormalizado|null}, detalle:[{...}]}. El total esperado es el del QR si lo hay. */
    default Map<String, Object> esperado(DatosComprobante d) {
        Map<String, Object> cabecera = new LinkedHashMap<>();
        cabecera.put("numeroComprobante", d.numeroImpreso());
        cabecera.put("cuitEmisor", d.emisorCuit());
        cabecera.put("fechaEmision", d.fechaEmision().format(DateTimeFormatter.ISO_LOCAL_DATE));
        cabecera.put("importeTotal", d.qr() != null ? d.qr().importe() : d.total());
        cabecera.put("numeroOrdenCompra", null);
        agregarCabecera(cabecera, d);
        List<Map<String, Object>> detalle = new ArrayList<>();
        for (DatosComprobante.Linea l : d.lineas()) {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("concepto", l.concepto());
            fila.put("cantidad", l.cantidad());
            fila.put("importe", l.importe());
            detalle.add(fila);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("cabecera", cabecera);
        res.put("detalle", detalle);
        return res;
    }

    /** Gancho para campos de cabecera propios del diseño. */
    default void agregarCabecera(Map<String, Object> cabecera, DatosComprobante d) {
    }

    /** Solicitud común; cada diseño aporta sus etiquetas (distintas entre diseños). */
    static SolicitudExtraccion solicitudBase(String etNumero, String etCuit, String etFecha, String etTotal,
                                             String etConcepto, String etCantidad, String etImporte,
                                             CampoSolicitado... extras) {
        List<CampoSolicitado> cab = new ArrayList<>(List.of(
            new CampoSolicitado("numeroComprobante", List.of(etNumero), "Número del comprobante.", TipoCampo.TEXTO),
            new CampoSolicitado("cuitEmisor", List.of(etCuit), "CUIT de quien emite el comprobante.", TipoCampo.CUIT),
            new CampoSolicitado("fechaEmision", List.of(etFecha), "Fecha de emisión.", TipoCampo.FECHA),
            new CampoSolicitado("importeTotal", List.of(etTotal), "Importe total del comprobante.", TipoCampo.IMPORTE),
            new CampoSolicitado("numeroOrdenCompra", List.of("Orden de compra"),
                "Número de orden de compra del cliente.", TipoCampo.TEXTO)));
        cab.addAll(List.of(extras));
        List<CampoSolicitado> det = List.of(
            new CampoSolicitado("concepto", List.of(etConcepto), "Descripción de la línea.", TipoCampo.TEXTO),
            new CampoSolicitado("cantidad", List.of(etCantidad), "Cantidad de la línea.", TipoCampo.NUMERO),
            new CampoSolicitado("importe", List.of(etImporte), "Importe de la línea.", TipoCampo.IMPORTE));
        return new SolicitudExtraccion(cab, det);
    }

    static BigDecimal dec(String v) {
        return new BigDecimal(v);
    }
}
