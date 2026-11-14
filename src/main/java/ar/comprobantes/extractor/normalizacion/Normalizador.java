package ar.comprobantes.extractor.normalizacion;

import ar.comprobantes.extractor.api.dto.TipoCampo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Normaliza el valor crudo devuelto por el LLM según el tipo de campo. Sin estado. */
@Component
public class Normalizador {

    private static final Pattern FECHA_NUMERICA =
        Pattern.compile("^(\\d{1,2})([/.-])(\\d{1,2})\\2(\\d{4}|\\d{2})$");
    private static final Pattern FECHA_ISO = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern FECHA_TEXTO =
        Pattern.compile("^(\\d{1,2})\\s+de\\s+(\\p{L}+)\\s+de\\s+(\\d{4})$");
    private static final Pattern MARCADORES_MONEDA =
        Pattern.compile("U\\$S|USD|ARS|pesos|\\$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOLO_NUMERICO = Pattern.compile("^[\\d.,]+$");
    private static final Pattern SOLO_DIGITOS = Pattern.compile("^\\d+$");

    private static final Map<String, Integer> MESES = Map.ofEntries(
        Map.entry("enero", 1), Map.entry("febrero", 2), Map.entry("marzo", 3),
        Map.entry("abril", 4), Map.entry("mayo", 5), Map.entry("junio", 6),
        Map.entry("julio", 7), Map.entry("agosto", 8), Map.entry("septiembre", 9),
        Map.entry("setiembre", 9), Map.entry("octubre", 10), Map.entry("noviembre", 11),
        Map.entry("diciembre", 12));

    public ResultadoNormalizacion normalizar(String crudo, TipoCampo tipo) {
        if (crudo == null || crudo.isBlank()) {
            return new ResultadoNormalizacion(null, null);
        }
        return switch (tipo) {
            case TEXTO -> new ResultadoNormalizacion(crudo.trim().replaceAll("\\s+", " "), null);
            case IMPORTE, NUMERO -> numero(crudo, tipo);
            case FECHA -> fecha(crudo);
            case CUIT -> cuit(crudo);
        };
    }

    private ResultadoNormalizacion numero(String crudo, TipoCampo tipo) {
        String s = crudo.trim();
        boolean negativo = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negativo = true;
            s = s.substring(1, s.length() - 1);
        }
        s = MARCADORES_MONEDA.matcher(s).replaceAll("").replaceAll("\\s", "");
        if (s.startsWith("-")) {
            negativo = true;
            s = s.substring(1);
        }
        String limpio = limpiarNumero(s);
        if (limpio == null) {
            return noInterpretable(crudo, tipo);
        }
        BigDecimal valor = new BigDecimal(limpio);
        return new ResultadoNormalizacion(negativo ? valor.negate() : valor, null);
    }

    /** Devuelve el número con punto decimal y sin separadores, o null si no es interpretable. */
    private static String limpiarNumero(String s) {
        if (!SOLO_NUMERICO.matcher(s).matches()) {
            return null;
        }
        int ultimoPunto = s.lastIndexOf('.');
        int ultimaComa = s.lastIndexOf(',');
        if (ultimoPunto >= 0 && ultimaComa >= 0) {
            char decimal = ultimoPunto > ultimaComa ? '.' : ',';
            char miles = decimal == '.' ? ',' : '.';
            int pos = Math.max(ultimoPunto, ultimaComa);
            String entera = s.substring(0, pos);
            String frac = s.substring(pos + 1);
            if (!agrupado(entera, miles) || frac.isEmpty() || frac.indexOf(decimal) >= 0
                || frac.indexOf(miles) >= 0) {
                return null;
            }
            return entera.replace(String.valueOf(miles), "") + "." + frac;
        }
        if (ultimaComa >= 0) {
            return unSeparador(s, ',');
        }
        if (ultimoPunto >= 0) {
            return unSeparador(s, '.');
        }
        return s;
    }

    /** Solo se usa un tipo de separador: una aparición es decimal (salvo agrupado inequívoco con punto). */
    private static String unSeparador(String s, char sep) {
        long cantidad = s.chars().filter(c -> c == sep).count();
        int pos = s.indexOf(sep);
        String entera = s.substring(0, pos);
        String frac = s.substring(pos + 1);
        if (cantidad > 1) {
            return agrupado(s, sep) ? s.replace(String.valueOf(sep), "") : null;
        }
        if (entera.isEmpty() || frac.isEmpty()) {
            return null;
        }
        boolean miles = sep == '.' && frac.length() == 3 && agrupado(s, sep);
        return miles ? entera + frac : entera + "." + frac;
    }

    /** Grupos de 3 dígitos separados por sep, primer grupo de 1 a 3 dígitos sin cero inicial. */
    private static boolean agrupado(String s, char sep) {
        String[] grupos = s.split(Pattern.quote(String.valueOf(sep)), -1);
        if (grupos.length < 2 || grupos[0].isEmpty() || grupos[0].length() > 3 || grupos[0].charAt(0) == '0') {
            return grupos.length == 1 && SOLO_DIGITOS.matcher(s).matches() && !s.isEmpty();
        }
        for (int i = 0; i < grupos.length; i++) {
            if (!grupos[i].matches(i == 0 ? "\\d{1,3}" : "\\d{3}")) {
                return false;
            }
        }
        return true;
    }

    private ResultadoNormalizacion fecha(String crudo) {
        String s = crudo.trim();
        try {
            Matcher m = FECHA_NUMERICA.matcher(s);
            if (m.matches()) {
                int anio = Integer.parseInt(m.group(4));
                if (m.group(4).length() == 2) {
                    anio += 2000;
                }
                return iso(anio, Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1)), crudo);
            }
            m = FECHA_ISO.matcher(s);
            if (m.matches()) {
                return iso(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), crudo);
            }
            m = FECHA_TEXTO.matcher(s);
            if (m.matches()) {
                Integer mes = MESES.get(m.group(2).toLowerCase(Locale.ROOT));
                if (mes != null) {
                    return iso(Integer.parseInt(m.group(3)), mes, Integer.parseInt(m.group(1)), crudo);
                }
            }
        } catch (java.time.DateTimeException e) {
            // fecha inexistente: se informa abajo
        }
        return noInterpretable(crudo, TipoCampo.FECHA);
    }

    private ResultadoNormalizacion iso(int anio, int mes, int dia, String crudo) {
        return new ResultadoNormalizacion(LocalDate.of(anio, mes, dia).toString(), null);
    }

    private ResultadoNormalizacion cuit(String crudo) {
        String digitos = crudo.replaceAll("\\D", "");
        if (digitos.length() != 11) {
            return noInterpretable(crudo, TipoCampo.CUIT);
        }
        if (!ValidadorCuit.esValido(digitos)) {
            return new ResultadoNormalizacion(digitos,
                "El CUIT no supera la validación del dígito verificador.");
        }
        return new ResultadoNormalizacion(digitos, null);
    }

    private ResultadoNormalizacion noInterpretable(String crudo, TipoCampo tipo) {
        return new ResultadoNormalizacion(crudo,
            "No se pudo interpretar '" + crudo + "' como " + tipo + "; se devuelve tal cual.");
    }
}
