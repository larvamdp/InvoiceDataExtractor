package ar.comprobantes.extractor.llm.nube;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.Proxy;
import java.nio.file.Path;
import java.time.Duration;
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
        assertThat(ErroresNube.traducir((Exception) error, "azure", "gpt", new ExtractorProperties.Proxy("localhost", 1, null, null, null)))
            .isInstanceOf(LlmNoDisponibleException.class);
    }

    @Test
    void httpsPorTunelConAutenticacion() throws Exception {
        servidor.stubFor(get(urlEqualTo("/v1/x")).willReturn(ok("seguro")));
        try (ProxyConnectDePrueba proxyConnect = new ProxyConnectDePrueba("Basic dTpj", servidor.httpsPort())) {
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
        try (ProxyConnectDePrueba proxyConnect = new ProxyConnectDePrueba("Basic dTpj", servidor.httpsPort())) {
            var props = new ExtractorProperties.Proxy("localhost", proxyConnect.puerto(), "u", "c", null);
            var cliente = HttpNube.restClient("https://localhost:" + servidor.httpsPort(), Duration.ofSeconds(10),
                props);
            assertThatThrownBy(() -> cliente.get().uri("/v1/x").retrieve().body(String.class))
                .isInstanceOf(RestClientException.class);
        }
    }
}
