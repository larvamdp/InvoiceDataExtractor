package ar.comprobantes.extractor.normalizacion;

/** Valida el dígito verificador de un CUIT/CUIL de 11 dígitos. */
public final class ValidadorCuit {

    private static final int[] PESOS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

    private ValidadorCuit() {
    }

    public static boolean esValido(String onceDigitos) {
        if (onceDigitos == null || !onceDigitos.matches("\\d{11}")) {
            return false;
        }
        int suma = 0;
        for (int i = 0; i < PESOS.length; i++) {
            suma += (onceDigitos.charAt(i) - '0') * PESOS[i];
        }
        int dv = 11 - (suma % 11);
        if (dv == 10) {
            return false;
        }
        if (dv == 11) {
            dv = 0;
        }
        return dv == onceDigitos.charAt(10) - '0';
    }
}
