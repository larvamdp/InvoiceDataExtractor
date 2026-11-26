package ar.comprobantes.extractor.llm.nube;

import ar.comprobantes.extractor.config.ExtractorProperties;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Construye clientes HTTP para proveedores en la nube: proxy con autenticación opcional y CA extra. */
public final class HttpNube {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private HttpNube() {
    }

    public static RestClient restClient(String baseUrl, Duration timeout, ExtractorProperties.Proxy proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            // HTTP/1.1 forzado: el upgrade h2c por defecto del cliente JDK falla contra servidores HTTP/1.1.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .sslContext(ConfianzaTls.contexto(proxy == null ? null : proxy.caExtra()));
        if (proxy != null && proxy.activo()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.puerto())));
            if (proxy.usuario() != null && !proxy.usuario().isBlank()) {
                // Basic ante proxy/túnel lo habilita ExtractorApplication.habilitarBasicEnProxy() al arrancar:
                // el JDK lee esas propiedades una sola vez, al construir el primer HttpClient.
                builder.authenticator(autenticadorDeProxy(proxy));
            }
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public static Proxy proxyJava(ExtractorProperties.Proxy proxy) {
        if (proxy == null || !proxy.activo()) {
            return Proxy.NO_PROXY;
        }
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.host(), proxy.puerto()));
    }

    /** Authenticator que entrega usuario/clave del proxy y responde solo a pedidos de tipo PROXY. */
    public static Authenticator autenticadorDeProxy(ExtractorProperties.Proxy proxy) {
        String usuario = proxy.usuario();
        char[] clave = (proxy.clave() == null ? "" : proxy.clave()).toCharArray();
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                return new PasswordAuthentication(usuario, clave.clone());
            }
        };
    }
}
