package ar.comprobantes.extractor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExtractorApplication {

    static final String ESQUEMAS_TUNEL = "jdk.http.auth.tunneling.disabledSchemes";
    static final String ESQUEMAS_PROXY = "jdk.http.auth.proxying.disabledSchemes";

    public static void main(String[] args) {
        habilitarBasicEnProxy();
        SpringApplication.run(ExtractorApplication.class, args);
    }

    /**
     * El JDK lee estas propiedades una sola vez, al construir el primer HttpClient de la JVM, por eso se fijan
     * antes de arrancar Spring. Habilita Basic ante proxies (también en túneles CONNECT). Un -D explícito del
     * operador tiene prioridad.
     */
    static void habilitarBasicEnProxy() {
        for (String clave : new String[] {ESQUEMAS_TUNEL, ESQUEMAS_PROXY}) {
            if (System.getProperty(clave) == null) {
                System.setProperty(clave, "");
            }
        }
    }
}
