package ar.comprobantes.extractor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExtractorApplication {

    static final String ESQUEMAS_TUNEL = "jdk.http.auth.tunneling.disabledSchemes";
    static final String ESQUEMAS_PROXY = "jdk.http.auth.proxying.disabledSchemes";
    static final String REINTENTOS_AUTH_HTTPCLIENT = "jdk.httpclient.auth.retrylimit";
    static final String REINTENTOS_TUNEL_URLCONNECTION = "http.maxRedirects";

    public static void main(String[] args) {
        configurarAutenticacionDeProxy();
        SpringApplication.run(ExtractorApplication.class, args);
    }

    /**
     * El JDK lee estas propiedades una sola vez (al cargar el cliente HTTP), por eso se fijan antes de arrancar
     * Spring. Habilita Basic ante proxies (también en túneles CONNECT) y limita los reintentos con una clave de
     * proxy rechazada: por defecto el JDK la reenvía hasta 4 veces (HttpClient) o 19 (HttpURLConnection, que usa
     * la librería de Google para el token) en cada solicitud, y cada intento cuenta para el bloqueo de la cuenta
     * en el proxy corporativo. Con estos valores: 2 y 1 intentos. Un -D explícito del operador tiene prioridad.
     */
    static void configurarAutenticacionDeProxy() {
        siNoEstaDefinida(ESQUEMAS_TUNEL, "");
        siNoEstaDefinida(ESQUEMAS_PROXY, "");
        siNoEstaDefinida(REINTENTOS_AUTH_HTTPCLIENT, "0");
        // El túnel cuenta el intento sin credenciales: 2 = uno sin clave y uno con clave. No afecta redirecciones
        // del token (la librería de Google las maneja sin HttpURLConnection).
        siNoEstaDefinida(REINTENTOS_TUNEL_URLCONNECTION, "2");
    }

    private static void siNoEstaDefinida(String clave, String valor) {
        if (System.getProperty(clave) == null) {
            System.setProperty(clave, valor);
        }
    }
}
