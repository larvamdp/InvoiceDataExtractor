package ar.comprobantes.extractor.qr;

import java.math.BigDecimal;

public record DatosQrArca(Integer ver, String fecha, Long cuit, Integer ptoVta, Integer tipoCmp, Long nroCmp,
                          BigDecimal importe, String moneda, BigDecimal ctz, Integer tipoDocRec, Long nroDocRec,
                          String tipoCodAut, Long codAut) {
}
