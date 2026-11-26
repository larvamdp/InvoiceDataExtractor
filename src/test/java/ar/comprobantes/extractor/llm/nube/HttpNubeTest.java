package ar.comprobantes.extractor.llm.nube;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import ar.comprobantes.extractor.config.ExtractorProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.Proxy;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpNubeTest {

    private static final String TUNEL = "jdk.http.auth.tunneling.disabledSchemes";
    private static final String PROXYING = "jdk.http.auth.proxying.disabledSchemes";

    private WireMockServer servidor;
    private String tunelPrevio;
    private String proxyingPrevio;

    @BeforeEach
    void iniciar() {
        tunelPrevio = System.getProperty(TUNEL);
        proxyingPrevio = System.getProperty(PROXYING);
        servidor = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        servidor.start();
    }

    @AfterEach
    void detener() {
        servidor.stop();
        restaurar(TUNEL, tunelPrevio);
        restaurar(PROXYING, proxyingPrevio);
    }

    private static void restaurar(String clave, String valor) {
        if (valor == null) {
            System.clearProperty(clave);
        } else {
            System.setProperty(clave, valor);
        }
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
    void habilitaBasicEnTunel() {
        System.setProperty(TUNEL, "Basic");
        HttpNube.restClient("http://proveedor.invalid", Duration.ofSeconds(5), proxy("u", "c"));
        assertThat(System.getProperty(TUNEL)).isEmpty();
        assertThat(System.getProperty(PROXYING)).isEmpty();
    }

    @Test
    void proxyJavaApuntaAlHost() {
        Proxy p = HttpNube.proxyJava(proxy(null, null));
        assertThat(p.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(p.address().toString()).contains("localhost");
    }
}
