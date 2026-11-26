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
            Authenticator.setDefault(HttpNube.autenticadorDeProxy(proxy));
        }
        // Se construye una sola vez: una CA extra inválida falla al arrancar, no dentro de token().
        HttpTransport transporte = crearTransporte(proxy);
        HttpTransportFactory fabrica = () -> transporte;
        try (InputStream in = Files.newInputStream(credenciales)) {
            GoogleCredentials cargadas = GoogleCredentials.fromStream(in, fabrica);
            if (!(cargadas instanceof ServiceAccountCredentials)) {
                throw new IllegalStateException(PREFIJO + "el archivo no es una cuenta de servicio");
            }
            // Sin reintentos internos de la librería (también reintentaría 429): un fallo se informa tal cual.
            this.credenciales = ((ServiceAccountCredentials) cargadas.createScoped(List.of(SCOPE)))
                .createWithCustomRetryStrategy(false);
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
            throw new IllegalStateException("EXTRACTOR_PROXY_CAEXTRA: no se pudo preparar la conexión TLS");
        }
    }

    @Override
    public String token() {
        try {
            credenciales.refreshIfExpired();
            return credenciales.getAccessToken().getTokenValue();
        } catch (IOException | RuntimeException e) {
            registrarFallo(e);
            throw new LlmNoDisponibleException("El servicio de IA no está disponible.");
        }
    }

    /** Distingue la causa sin registrar mensajes ni cuerpos de la librería (pueden traer datos de la cuenta). */
    private void registrarFallo(Exception e) {
        String via = hayProxy ? "vía proxy" : "directo";
        Integer status = null;
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof com.google.api.client.http.HttpResponseException h) {
                status = h.getStatusCode();
                break;
            }
        }
        if (status != null && (status == 400 || status == 401)) {
            log.warn("no se pudo obtener el token de Google ({}): tipo={} status={}: credenciales rechazadas por el proveedor",
                via, e.getClass().getSimpleName(), status);
        } else if (status != null) {
            log.warn("no se pudo obtener el token de Google ({}): tipo={} status={}",
                via, e.getClass().getSimpleName(), status);
        } else if (ErroresNube.esFalloDeAutenticacion(e)) {
            log.warn("no se pudo obtener el token de Google ({}): tipo={}: autenticación rechazada por el proxy o el proveedor",
                via, e.getClass().getSimpleName());
        } else {
            log.warn("no se pudo obtener el token de Google ({}): tipo={}", via, e.getClass().getSimpleName());
        }
    }
}
