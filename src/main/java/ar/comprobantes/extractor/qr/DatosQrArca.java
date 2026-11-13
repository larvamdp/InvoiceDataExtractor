package ar.comprobantes.extractor.qr;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record DatosQrArca(Integer ver, String fecha, Long cuit, Integer ptoVta, Integer tipoCmp, Long nroCmp,
                          BigDecimal importe, String moneda, BigDecimal ctz, Integer tipoDocRec, Long nroDocRec,
                          String tipoCodAut, Long codAut) {

    public static final List<String> CLAVES = List.of("ver", "fecha", "cuit", "ptoVta", "tipoCmp", "nroCmp",
            "importe", "moneda", "ctz", "tipoDocRec", "nroDocRec", "tipoCodAut", "codAut");

    public static final Set<String> CLAVES_CODIGO = Set.of("tipoCmp", "moneda", "tipoDocRec", "tipoCodAut");

    public Object valorDeClave(String clave) {
        return switch (clave) {
            case "ver" -> ver;
            case "fecha" -> fecha;
            case "cuit" -> cuit;
            case "ptoVta" -> ptoVta;
            case "tipoCmp" -> tipoCmp;
            case "nroCmp" -> nroCmp;
            case "importe" -> importe;
            case "moneda" -> moneda;
            case "ctz" -> ctz;
            case "tipoDocRec" -> tipoDocRec;
            case "nroDocRec" -> nroDocRec;
            case "tipoCodAut" -> tipoCodAut;
            case "codAut" -> codAut;
            default -> null;
        };
    }
}
