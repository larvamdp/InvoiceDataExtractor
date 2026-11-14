package ar.comprobantes.extractor.sintetico;

import ar.comprobantes.extractor.qr.DatosQrArca;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Datos 100% ficticios de un comprobante. qr es null si el comprobante no lleva QR. */
public record DatosComprobante(
    String emisorRazonSocial,
    String emisorCuit,
    String emisorDomicilio,
    String receptorRazonSocial,
    String receptorCuit,
    String tipo,
    int tipoCmp,
    int puntoVenta,
    long numero,
    LocalDate fechaEmision,
    LocalDate vencimiento1,
    LocalDate vencimiento2,
    String numeroMedidor,
    List<Linea> lineas,
    BigDecimal neto,
    BigDecimal iva,
    BigDecimal total,
    DatosQrArca qr,
    BigDecimal totalImpreso) {

    public record Linea(String concepto, BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal importe) {
    }

    /** Número tal como se imprime: 0003-00012345. */
    public String numeroImpreso() {
        return "%04d-%08d".formatted(puntoVenta, numero);
    }
}
