package ar.comprobantes.extractor.conciliacion;

import ar.comprobantes.extractor.api.dto.TipoCampo;
import ar.comprobantes.extractor.normalizacion.Normalizador;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Verifica que un valor extraído aparezca (con cualquier formato) en el texto del documento. */
@Component
public class VerificadorTextual {

    private static final Pattern TOKEN_NUMERICO = Pattern.compile("(?:(?<![\\d\\p{L}])-)?\\(?\\$?\\s?\\d[\\d.,]*");
    private static final Pattern TOKEN_FECHA = Pattern.compile(
        "\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}\\s+de\\s+\\p{L}+\\s+de\\s+\\d{4}",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN_CUIT = Pattern.compile("(?<!\\d)\\d{2}-?\\d{8}-?\\d(?!\\d)");

    private final Normalizador normalizador;

    public VerificadorTextual(Normalizador normalizador) {
        this.normalizador = normalizador;
    }

    public boolean aparece(String crudo, Object normalizado, TipoCampo tipo, String texto) {
        if (texto == null || (crudo == null && normalizado == null)) {
            return false;
        }
        return switch (tipo) {
            case TEXTO -> texto(crudo != null ? crudo : String.valueOf(normalizado), texto);
            case IMPORTE, NUMERO -> numero(normalizado, texto);
            case FECHA -> fecha(String.valueOf(normalizado), texto);
            case CUIT -> cuit(String.valueOf(normalizado), texto);
        };
    }

    private static String colapsar(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private boolean texto(String crudo, String texto) {
        return colapsar(texto).contains(colapsar(crudo));
    }

    private boolean numero(Object normalizado, String texto) {
        if (!(normalizado instanceof BigDecimal buscado)) {
            return false;
        }
        Matcher m = TOKEN_NUMERICO.matcher(texto);
        while (m.find()) {
            String token = m.group().replaceAll("[.,]+$", "");
            if (normalizador.normalizar(token, TipoCampo.IMPORTE).valor() instanceof BigDecimal v
                && v.compareTo(buscado) == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean fecha(String iso, String texto) {
        Matcher m = TOKEN_FECHA.matcher(texto);
        while (m.find()) {
            if (iso.equals(normalizador.normalizar(m.group(), TipoCampo.FECHA).valor())) {
                return true;
            }
        }
        return false;
    }

    private boolean cuit(String valor, String texto) {
        String digitos = valor.replaceAll("\\D", "");
        Matcher m = TOKEN_CUIT.matcher(texto);
        while (m.find()) {
            if (digitos.equals(m.group().replace("-", ""))) {
                return true;
            }
        }
        return false;
    }
}
