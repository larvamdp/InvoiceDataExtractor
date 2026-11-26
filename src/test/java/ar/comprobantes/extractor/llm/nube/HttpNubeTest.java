package ar.comprobantes.extractor.llm.nube;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

class HttpNubeTest {

    private WireMockServer servidor;

    @BeforeEach
    void iniciar() throws Exception {
        Path p12 = recurso("/tls/servidor.p12");
        servidor = new WireMockServer(WireMockConfiguration.options().dynamicPort().dynamicHttpsPort()
            .keystorePath(p12.toString()).keystorePassword("changeit").keyManagerPassword("changeit")
            .keystoreType("PKCS12"));
        servidor.start();
    }

    @AfterEach
    void detener() {
        servidor.stop();
    }

    private static Path recurso(String ruta) throws Exception {
        return Path.of(HttpNubeTest.class.getResource(ruta).toURI());
    }

    private ExtractorProperties.Proxy proxy(String usuario, String clave) {
        return new ExtractorProperties.Proxy("localhost", servidor.port(), usuario, clave, null);
    }

    @Test
    void pasaPorElProxy() {
        servidor.stubFor(get(urlEqualTo("/v1/x")).willReturn(ok("hola")));
        String r = HttpNube.restClient("http://proveedor.invalid", Duration.ofSeconds(5), proxy(null, null))
            .get().uri("/v1/x").retrieve().body(String.class);
        assertThat(r).isEqualTo("hola");
        servidor.verify(1, getRequestedFor(urlEqualTo("/v1/x")));
    }

    @Test
    void autenticaAnteElProxy() {
        servidor.stubFor(get(urlEqualTo("/v1/x")).atPriority(2).willReturn(aResponse().withStatus(407)
            .withHeader("Proxy-Authenticate", "Basic realm=\"p\"")));
        servidor.stubFor(get(urlEqualTo("/v1/x")).atPriority(1)
            .withHeader("Proxy-Authorization", equalTo("Basic dTpj")).willReturn(ok("autenticado")));
        String r = HttpNube.restClient("http://proveedor.invalid", Duration.ofSeconds(5), proxy("u", "c"))
            .get().uri("/v1/x").retrieve().body(String.class);
        assertThat(r).isEqualTo("autenticado");
    }

    @Test
    void sinProxyVaDirecto() {
        servidor.stubFor(get(urlEqualTo("/v1/x")).willReturn(ok("directo")));
        ExtractorProperties.Proxy sin = new ExtractorProperties.Proxy(null, null, null, null, null);
        String r = HttpNube.restClient("http://localhost:" + servidor.port(), Duration.ofSeconds(5), sin)
            .get().uri("/v1/x").retrieve().body(String.class);
        assertThat(r).isEqualTo("directo");
        assertThat(HttpNube.proxyJava(sin)).isSameAs(Proxy.NO_PROXY);
    }

    @Test
    void proxyJavaApuntaAlHost() {
        Proxy p = HttpNube.proxyJava(proxy(null, null));
        assertThat(p.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(p.address().toString()).contains("localhost");
    }

    @Test
    void credencialesDeProxyNoSeEnvianAlProveedor() {
        servidor.stubFor(get(urlEqualTo("/v1/x")).willReturn(aResponse().withStatus(401)
            .withHeader("WWW-Authenticate", "Basic realm=\"x\"")));
        var cliente = HttpNube.restClient("http://proveedor.invalid", Duration.ofSeconds(5), proxy("u", "c"));
        Throwable error = org.junit.jupiter.api.Assertions.assertThrows(RestClientException.class,
            () -> cliente.get().uri("/v1/x").retrieve().body(String.class));
        servidor.verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/v1/x")));
        servidor.verify(0, getRequestedFor(urlEqualTo("/v1/x")).withHeader("Authorization", matching(".*")));
        assertThat(ErroresNube.traducir((Exception) error, "azure", "gpt", true))
            .isInstanceOf(LlmNoDisponibleException.class);
    }

    @Test
    void httpsPorTunelConAutenticacion() throws Exception {
        servidor.stubFor(get(urlEqualTo("/v1/x")).willReturn(ok("seguro")));
        try (ProxyConnect proxyConnect = new ProxyConnect("Basic dTpj", servidor.httpsPort())) {
            var props = new ExtractorProperties.Proxy("localhost", proxyConnect.puerto(), "u", "c",
                recurso("/tls/ca-prueba.pem"));
            String r = HttpNube.restClient("https://localhost:" + servidor.httpsPort(), Duration.ofSeconds(10), props)
                .get().uri("/v1/x").retrieve().body(String.class);
            assertThat(r).isEqualTo("seguro");
            assertThat(proxyConnect.connectAutenticados.get()).isEqualTo(1);
        }
    }

    @Test
    void httpsSinCaExtraFalla() throws Exception {
        servidor.stubFor(get(urlEqualTo("/v1/x")).willReturn(ok("seguro")));
        try (ProxyConnect proxyConnect = new ProxyConnect("Basic dTpj", servidor.httpsPort())) {
            var props = new ExtractorProperties.Proxy("localhost", proxyConnect.puerto(), "u", "c", null);
            var cliente = HttpNube.restClient("https://localhost:" + servidor.httpsPort(), Duration.ofSeconds(10),
                props);
            assertThatThrownBy(() -> cliente.get().uri("/v1/x").retrieve().body(String.class))
                .isInstanceOf(RestClientException.class);
        }
    }

    /** Proxy CONNECT mínimo: 407 sin Proxy-Authorization esperado; con él, abre el túnel hacia localhost:destino. */
    private static final class ProxyConnect implements AutoCloseable {
        final AtomicInteger connectAutenticados = new AtomicInteger();
        private final ServerSocket socket;
        private final String esperado;
        private final int destino;

        ProxyConnect(String esperado, int destino) throws IOException {
            this.socket = new ServerSocket(0);
            this.esperado = esperado;
            this.destino = destino;
            Thread t = new Thread(this::aceptar, "proxy-connect-prueba");
            t.setDaemon(true);
            t.start();
        }

        int puerto() {
            return socket.getLocalPort();
        }

        private void aceptar() {
            try {
                while (!socket.isClosed()) {
                    Socket cliente = socket.accept();
                    Thread t = new Thread(() -> atender(cliente));
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException ignorada) {
                // socket cerrado
            }
        }

        private void atender(Socket cliente) {
            try (cliente) {
                InputStream in = cliente.getInputStream();
                OutputStream out = cliente.getOutputStream();
                while (true) {
                    String cabecera = leerCabecera(in);
                    if (cabecera == null) {
                        return;
                    }
                    if (!cabecera.startsWith("CONNECT ")) {
                        return;
                    }
                    if (!cabecera.contains("Proxy-Authorization: " + esperado)) {
                        out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                            + "Proxy-Authenticate: Basic realm=\"p\"\r\nContent-Length: 0\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        continue;
                    }
                    connectAutenticados.incrementAndGet();
                    try (Socket destinoSocket = new Socket("localhost", destino)) {
                        out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        Thread hacia = new Thread(() -> copiar(in, destinoSocketOut(destinoSocket)));
                        hacia.setDaemon(true);
                        hacia.start();
                        copiar(destinoSocketIn(destinoSocket), out);
                    }
                    return;
                }
            } catch (IOException ignorada) {
                // conexión cerrada
            }
        }

        private static OutputStream destinoSocketOut(Socket s) {
            try {
                return s.getOutputStream();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static InputStream destinoSocketIn(Socket s) {
            try {
                return s.getInputStream();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void copiar(InputStream in, OutputStream out) {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignorada) {
                // fin del túnel
            }
        }

        /** Lee hasta CRLFCRLF byte a byte para no consumir bytes del túnel. */
        private static String leerCabecera(InputStream in) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                bos.write(b);
                byte[] a = bos.toByteArray();
                int n = a.length;
                if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') {
                    return bos.toString(StandardCharsets.US_ASCII);
                }
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
