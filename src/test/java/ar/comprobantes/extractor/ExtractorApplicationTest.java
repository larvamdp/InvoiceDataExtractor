package ar.comprobantes.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExtractorApplicationTest {

    private String tunelPrevio;
    private String proxyPrevio;

    @BeforeEach
    void guardar() {
        tunelPrevio = System.getProperty(ExtractorApplication.ESQUEMAS_TUNEL);
        proxyPrevio = System.getProperty(ExtractorApplication.ESQUEMAS_PROXY);
        System.clearProperty(ExtractorApplication.ESQUEMAS_TUNEL);
        System.clearProperty(ExtractorApplication.ESQUEMAS_PROXY);
    }

    @AfterEach
    void restaurar() {
        restaurar(ExtractorApplication.ESQUEMAS_TUNEL, tunelPrevio);
        restaurar(ExtractorApplication.ESQUEMAS_PROXY, proxyPrevio);
    }

    private static void restaurar(String clave, String valor) {
        if (valor == null) {
            System.clearProperty(clave);
        } else {
            System.setProperty(clave, valor);
        }
    }

    @Test
    void habilitaBasicSiNoEstanDefinidas() {
        ExtractorApplication.habilitarBasicEnProxy();
        assertThat(System.getProperty(ExtractorApplication.ESQUEMAS_TUNEL)).isEmpty();
        assertThat(System.getProperty(ExtractorApplication.ESQUEMAS_PROXY)).isEmpty();
    }

    @Test
    void respetaElValorExplicitoDelOperador() {
        System.setProperty(ExtractorApplication.ESQUEMAS_TUNEL, "Basic");
        ExtractorApplication.habilitarBasicEnProxy();
        assertThat(System.getProperty(ExtractorApplication.ESQUEMAS_TUNEL)).isEqualTo("Basic");
        assertThat(System.getProperty(ExtractorApplication.ESQUEMAS_PROXY)).isEmpty();
    }
}
