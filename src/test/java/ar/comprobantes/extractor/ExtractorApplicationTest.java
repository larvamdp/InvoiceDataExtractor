package ar.comprobantes.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExtractorApplicationTest {

    private String tunelPrevio;
    private String proxyPrevio;
    private String reintentosPrevio;
    private String tunelReintentosPrevio;

    @BeforeEach
    void guardar() {
        tunelPrevio = System.getProperty(ExtractorApplication.ESQUEMAS_TUNEL);
        proxyPrevio = System.getProperty(ExtractorApplication.ESQUEMAS_PROXY);
        reintentosPrevio = System.getProperty(ExtractorApplication.REINTENTOS_AUTH_HTTPCLIENT);
        tunelReintentosPrevio = System.getProperty(ExtractorApplication.REINTENTOS_TUNEL_URLCONNECTION);
        System.clearProperty(ExtractorApplication.ESQUEMAS_TUNEL);
        System.clearProperty(ExtractorApplication.ESQUEMAS_PROXY);
        System.clearProperty(ExtractorApplication.REINTENTOS_AUTH_HTTPCLIENT);
        System.clearProperty(ExtractorApplication.REINTENTOS_TUNEL_URLCONNECTION);
    }

    @AfterEach
    void restaurar() {
        restaurar(ExtractorApplication.ESQUEMAS_TUNEL, tunelPrevio);
        restaurar(ExtractorApplication.ESQUEMAS_PROXY, proxyPrevio);
        restaurar(ExtractorApplication.REINTENTOS_AUTH_HTTPCLIENT, reintentosPrevio);
        restaurar(ExtractorApplication.REINTENTOS_TUNEL_URLCONNECTION, tunelReintentosPrevio);
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
        ExtractorApplication.configurarAutenticacionDeProxy();
        assertThat(System.getProperty(ExtractorApplication.ESQUEMAS_TUNEL)).isEmpty();
        assertThat(System.getProperty(ExtractorApplication.ESQUEMAS_PROXY)).isEmpty();
    }

    @Test
    void respetaElValorExplicitoDelOperador() {
        System.setProperty(ExtractorApplication.ESQUEMAS_TUNEL, "Basic");
        ExtractorApplication.configurarAutenticacionDeProxy();
        assertThat(System.getProperty(ExtractorApplication.ESQUEMAS_TUNEL)).isEqualTo("Basic");
        assertThat(System.getProperty(ExtractorApplication.ESQUEMAS_PROXY)).isEmpty();
    }

    @Test
    void limitaLosReintentosConLaClaveDelProxy() {
        ExtractorApplication.configurarAutenticacionDeProxy();
        assertThat(System.getProperty(ExtractorApplication.REINTENTOS_AUTH_HTTPCLIENT)).isEqualTo("0");
        assertThat(System.getProperty(ExtractorApplication.REINTENTOS_TUNEL_URLCONNECTION)).isEqualTo("2");
    }

    @Test
    void respetaReintentosExplicitosDelOperador() {
        System.setProperty(ExtractorApplication.REINTENTOS_AUTH_HTTPCLIENT, "3");
        ExtractorApplication.configurarAutenticacionDeProxy();
        assertThat(System.getProperty(ExtractorApplication.REINTENTOS_AUTH_HTTPCLIENT)).isEqualTo("3");
    }
}
