package ar.comprobantes.extractor.conciliacion;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.Fuente;
import ar.comprobantes.extractor.api.dto.TipoCampo;
import ar.comprobantes.extractor.api.dto.ValorCampo;
import ar.comprobantes.extractor.llm.ValorLlm;
import ar.comprobantes.extractor.normalizacion.Normalizador;
import ar.comprobantes.extractor.normalizacion.ResultadoNormalizacion;
import ar.comprobantes.extractor.qr.DatosQrArca;
import ar.comprobantes.extractor.normalizacion.ValidadorCuit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Combina el valor del LLM con los datos del QR de ARCA según las reglas de conciliación. */
@Component
public class ConciliadorQr {

    private static final Pattern GRUPO_DIGITOS = Pattern.compile("\\d+");

    private final Normalizador normalizador;

    public ConciliadorQr(Normalizador normalizador) {
        this.normalizador = normalizador;
    }

    public ValorCampo conciliar(CampoSolicitado campo, ValorLlm llm, DatosQrArca qr) {
        ResultadoNormalizacion doc = normalizador.normalizar(llm.valor(), campo.tipo());
        List<String> claves = qr == null || llm.clavesQr() == null ? List.<String>of() : llm.clavesQr();
        if (claves.size() == 1 && campo.tipo() == TipoCampo.TEXTO && doc.valor() != null
                && qr.valorDeClave(claves.get(0)) != null && !DatosQrArca.CLAVES_CODIGO.contains(claves.get(0))) {
            ValorCampo compuesto = conservarCompuesto(llm, doc.valor(), claves.get(0), qr);
            if (compuesto != null) {
                return compuesto;
            }
        }
        boolean todasPresentes = claves.stream().allMatch(c -> qr.valorDeClave(c) != null);

        if (claves.size() == 1 && todasPresentes && !DatosQrArca.CLAVES_CODIGO.contains(claves.get(0))) {
            Object valorQr = qr.valorDeClave(claves.get(0));
            Object normalizadoQr = normalizarQr(valorQr, campo.tipo());
            if (normalizadoQr != null) {
                String descripcion = llm.descripcion();
                if (doc.valor() != null && !iguales(doc.valor(), normalizadoQr)) {
                    descripcion = unir(descripcion, "En el documento figura " + llm.valor() + "; el QR indica "
                        + texto(valorQr) + "; se usó el QR.");
                }
                return new ValorCampo(normalizadoQr, descripcion, Fuente.QR_ARCA);
            }
        }

        String descripcion = unir(llm.descripcion(), doc.observacion());
        if (doc.valor() != null && !claves.isEmpty() && todasPresentes) {
            descripcion = unir(descripcion, verificar(doc.valor(), claves, qr));
        }
        return new ValorCampo(doc.valor(), descripcion, doc.valor() == null ? null : Fuente.DOCUMENTO);
    }

    /**
     * Si el texto del documento tiene, además del valor del QR, otros números (p. ej. "0007-00045821" frente a
     * nroCmp=45821), no se recorta al valor del QR: se conserva el del documento y se verifica. El par
     * punto de venta / número se completa con la clave complementaria. Devuelve null si no aplica.
     */
    private static ValorCampo conservarCompuesto(ValorLlm llm, Object valorDocumento, String clave, DatosQrArca qr) {
        List<String> grupos = grupos(valorDocumento);
        if (grupos.size() < 2 || !grupos.contains(sinCeros(texto(qr.valorDeClave(clave))))) {
            return null;
        }
        String complementaria = switch (clave) {
            case "nroCmp" -> "ptoVta";
            case "ptoVta" -> "nroCmp";
            default -> null;
        };
        String nota;
        if (complementaria != null && qr.valorDeClave(complementaria) != null) {
            nota = verificar(valorDocumento, List.of("ptoVta", "nroCmp"), qr);
        } else {
            nota = "Contiene el valor del QR de ARCA (" + clave + "=" + texto(qr.valorDeClave(clave))
                + "); verificar el resto.";
        }
        return new ValorCampo(valorDocumento, unir(llm.descripcion(), nota), Fuente.DOCUMENTO);
    }

    private static List<String> grupos(Object valor) {
        List<String> grupos = new ArrayList<>();
        Matcher m = GRUPO_DIGITOS.matcher(texto(valor));
        while (m.find()) {
            grupos.add(sinCeros(m.group()));
        }
        return grupos;
    }

    private static Object normalizarQr(Object valor, TipoCampo tipo) {
        try {
            return switch (tipo) {
                case TEXTO -> valor.toString();
                case FECHA -> LocalDate.parse(valor.toString(), DateTimeFormatter.ISO_LOCAL_DATE).toString();
                case CUIT -> cuitValido(valor.toString());
                case IMPORTE, NUMERO -> valor instanceof BigDecimal b ? b : new BigDecimal(valor.toString());
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            return null; // la clave elegida por el LLM no es del tipo pedido: no se aplica el QR
        }
    }

    private static String cuitValido(String valor) {
        String digitos = valor.replaceAll("\\D", "");
        return digitos.length() == 11 && ValidadorCuit.esValido(digitos) ? digitos : null;
    }

    private static String texto(Object valor) {
        return valor instanceof BigDecimal b ? b.toPlainString() : valor.toString();
    }

    private static boolean iguales(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    /** Devuelve la nota de verificación, o null si solo hay claves de código. */
    private static String verificar(Object valorDocumento, List<String> claves, DatosQrArca qr) {
        List<String> noCodigo = claves.stream().filter(c -> !DatosQrArca.CLAVES_CODIGO.contains(c)).toList();
        if (noCodigo.isEmpty()) {
            return null;
        }
        List<String> delDocumento = grupos(valorDocumento);
        List<String> delQr = noCodigo.stream().map(c -> sinCeros(texto(qr.valorDeClave(c)))).toList();
        if (ordenada(delDocumento).equals(ordenada(delQr))) {
            return "Verificado con QR de ARCA.";
        }
        String detalle = noCodigo.stream().map(c -> c + "=" + texto(qr.valorDeClave(c)))
            .collect(Collectors.joining(", "));
        return "No coincide con el QR de ARCA (" + detalle + "); verificar.";
    }

    private static String sinCeros(String s) {
        return s.replaceFirst("^0+(?=.)", "");
    }

    private static List<String> ordenada(List<String> valores) {
        List<String> copia = new ArrayList<>(valores);
        Collections.sort(copia);
        return copia;
    }

    private static String unir(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + " " + b;
    }
}
