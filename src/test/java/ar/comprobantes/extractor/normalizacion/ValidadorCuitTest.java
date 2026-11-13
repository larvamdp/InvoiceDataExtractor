package ar.comprobantes.extractor.normalizacion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ValidadorCuitTest {

    @ParameterizedTest
    @CsvSource({"20123456786,true", "30712345671,true", "20123456780,false", "30712345678,false"})
    void validaCuit(String cuit, boolean esperado) {
        assertThat(ValidadorCuit.esValido(cuit)).isEqualTo(esperado);
    }
}
