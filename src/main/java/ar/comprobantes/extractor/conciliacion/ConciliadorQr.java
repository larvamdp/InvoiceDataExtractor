package ar.comprobantes.extractor.conciliacion;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.Fuente;
import ar.comprobantes.extractor.api.dto.TipoCampo;
import ar.comprobantes.extractor.api.dto.ValorCampo;
import ar.comprobantes.extractor.llm.ValorLlm;
import ar.comprobantes.extractor.normalizacion.Normalizador;
import ar.comprobantes.extractor.normalizacion.ResultadoNormalizacion;
import ar.comprobantes.extractor.qr.DatosQrArca;
import java.math.BigDecimal;
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
        List<String> claves = clavesDisponibles(llm.clavesQr(), qr);

        if (claves.size() == 1 && !DatosQrArca.CLAVES_CODIGO.contains(claves.get(0))) {
            Object valorQr = qr.valorDeClave(claves.get(0));
            Object normalizadoQr = normalizarQr(valorQr, campo.tipo());
            String descripcion = llm.descripcion();
            if (doc.valor() != null && !iguales(doc.valor(), normalizadoQr)) {
                descripcion = unir(descripcion, "En el documento figura " + llm.valor() + "; el QR indica "
                    + texto(valorQr) + "; se usó el QR.");
            }
            return new ValorCampo(normalizadoQr, descripcion, Fuente.QR_ARCA);
        }

        String descripcion = unir(llm.descripcion(), doc.observacion());
        if (doc.valor() != null && !claves.isEmpty()) {
            descripcion = unir(descripcion, verificar(doc.valor(), claves, qr));
        }
        return new ValorCampo(doc.valor(), descripcion, doc.valor() == null ? null : Fuente.DOCUMENTO);
    }

    private static List<String> clavesDisponibles(List<String> clavesQr, DatosQrArca qr) {
        if (qr == null || clavesQr == null) {
            return List.of();
        }
        return clavesQr.stream().filter(c -> qr.valorDeClave(c) != null).toList();
    }

    private static Object normalizarQr(Object valor, TipoCampo tipo) {
        return switch (tipo) {
            case TEXTO, FECHA -> valor.toString();
            case CUIT -> valor.toString().replaceAll("\\D", "");
            case IMPORTE, NUMERO -> valor instanceof BigDecimal b ? b : new BigDecimal(valor.toString());
        };
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
        List<String> delDocumento = new ArrayList<>();
        Matcher m = GRUPO_DIGITOS.matcher(texto(valorDocumento));
        while (m.find()) {
            delDocumento.add(sinCeros(m.group()));
        }
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
