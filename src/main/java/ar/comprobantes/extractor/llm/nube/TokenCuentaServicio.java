package ar.comprobantes.extractor.llm.nube;

import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Token OAuth2 de Google a partir de una cuenta de servicio; el pedido de token sale por el proxy configurado. */
public class TokenCuentaServicio implements ProveedorToken {

    private static final Logger log = LoggerFactory.getLogger(TokenCuentaServicio.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final String PREFIJO = "EXTRACTOR_VERTEX_CREDENCIALES: ";

    private final GoogleCredentials credenciales;
    private final boolean hayProxy;

    public TokenCuentaServicio(Path credenciales, ExtractorProperties.Proxy proxy) {
        this.hayProxy = proxy != null && proxy.activo();
        if (credenciales == null || !Files.isRegularFile(credenciales)) {
            throw new IllegalStateException(PREFIJO + "el archivo de credenciales no existe");
        }
        if (hayProxy && proxy.usuario() != null && !proxy.usuario().isBlank()) {
            instalarAutenticadorDeProxy(proxy.usuario(), proxy.clave() == null ? "" : proxy.clave());
        }
        HttpTransportFactory fabrica = () -> crearTransporte(proxy);
        try (InputStream in = Files.newInputStream(credenciales)) {
            GoogleCredentials cargadas = GoogleCredentials.fromStream(in, fabrica);
            if (!(cargadas instanceof ServiceAccountCredentials)) {
                throw new IllegalStateException(PREFIJO + "el archivo no es una cuenta de servicio");
            }
            this.credenciales = cargadas.createScoped(List.of(SCOPE));
        } catch (IllegalStateException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(PREFIJO + "no se pudo leer el archivo de credenciales");
        }
    }

    private static HttpTransport crearTransporte(ExtractorProperties.Proxy proxy) {
        try {
            return new NetHttpTransport.Builder()
                .setProxy(HttpNube.proxyJava(proxy))
                .trustCertificates(ConfianzaTls.keyStore(proxy == null ? null : proxy.caExtra()))
                .build();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(PREFIJO + "no se pudo preparar la conexión TLS");
        }
    }

    private static void instalarAutenticadorDeProxy(String usuario, String clave) {
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                return new PasswordAuthentication(usuario, clave.toCharArray());
            }
        });
    }

    @Override
    public String token() {
        try {
            credenciales.refreshIfExpired();
            return credenciales.getAccessToken().getTokenValue();
        } catch (IOException | RuntimeException e) {
            log.warn("no se pudo obtener el token de Google ({})", hayProxy ? "vía proxy" : "directo");
            throw new LlmNoDisponibleException("El servicio de IA no está disponible.");
        }
    }
}
