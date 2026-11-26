package ar.comprobantes.extractor.llm.nube;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.Authenticator;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TokenCuentaServicioTest {

    private static final String TOKEN_OK = "{\"access_token\":\"tok-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";

    @TempDir
    Path dir;

    private WireMockServer proxy;
    private Authenticator autenticadorPrevio;

    @BeforeEach
    void iniciar() {
        autenticadorPrevio = Authenticator.getDefault();
        proxy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        proxy.start();
    }

    @AfterEach
    void detener() {
        proxy.stop();
        Authenticator.setDefault(autenticadorPrevio);
    }

    private TokenCuentaServicio crear() throws Exception {
        Path json = CuentaServicioDePrueba.escribir(dir, "http://oauth.invalid/token");
        return new TokenCuentaServicio(json,
            new ExtractorProperties.Proxy("localhost", proxy.port(), null, null, null));
    }

    @Test
    void obtieneTokenViaProxy() throws Exception {
        proxy.stubFor(post(urlEqualTo("/token")).willReturn(okJson(TOKEN_OK)));
        assertThat(crear().token()).isEqualTo("tok-1");
        proxy.verify(1, postRequestedFor(urlEqualTo("/token")));
    }

    @Test
    void cacheaElToken() throws Exception {
        proxy.stubFor(post(urlEqualTo("/token")).willReturn(okJson(TOKEN_OK)));
        TokenCuentaServicio t = crear();
        t.token();
        t.token();
        proxy.verify(1, postRequestedFor(urlEqualTo("/token")));
    }

    @Test
    void tokenFallaEs503() throws Exception {
        proxy.stubFor(post(urlEqualTo("/token")).willReturn(serverError()));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> crear().token()).isInstanceOf(LlmNoDisponibleException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(4));
        proxy.verify(1, postRequestedFor(urlEqualTo("/token")));
    }

    @Test
    void tokenConCuota429SinReintentos() throws Exception {
        proxy.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(429)));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> crear().token()).isInstanceOf(LlmNoDisponibleException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(4));
        proxy.verify(1, postRequestedFor(urlEqualTo("/token")));
    }

    @Test
    void credencialesRechazadasLoguean400SinCuerpo(CapturedOutput out) throws Exception {
        proxy.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(400)
            .withBody("{\"error\":\"invalid_grant\"}")));
        assertThatThrownBy(() -> crear().token()).isInstanceOf(LlmNoDisponibleException.class);
        assertThat(out.getAll()).contains("status=400").contains("credenciales rechazadas por el proveedor")
            .contains("tipo=").doesNotContain("invalid_grant");
    }

    @Test
    void falloDeTokenSinRespuestaHttpNoDiceCredenciales(CapturedOutput out) throws Exception {
        proxy.stubFor(post(urlEqualTo("/token")).willReturn(serverError()));
        assertThatThrownBy(() -> crear().token()).isInstanceOf(LlmNoDisponibleException.class);
        assertThat(out.getAll()).contains("status=500").doesNotContain("credenciales rechazadas");
    }

    @Test
    void caExtraInvalidaFallaAlCrear() throws Exception {
        Path json = CuentaServicioDePrueba.escribir(dir, "http://oauth.invalid/token");
        Path pem = dir.resolve("mala.pem");
        Files.writeString(pem, "no es un certificado");
        assertThatThrownBy(() -> new TokenCuentaServicio(json,
            new ExtractorProperties.Proxy("localhost", proxy.port(), null, null, pem)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EXTRACTOR_PROXY_CAEXTRA");
    }

    @Test
    void httpsPorTunelConAutenticacion() throws Exception {
        Path p12 = Path.of(getClass().getResource("/tls/servidor.p12").toURI());
        WireMockServer https = new WireMockServer(WireMockConfiguration.options().dynamicPort().dynamicHttpsPort()
            .keystorePath(p12.toString()).keystorePassword("changeit").keyManagerPassword("changeit")
            .keystoreType("PKCS12"));
        https.start();
        try (ProxyConnectDePrueba tunel = new ProxyConnectDePrueba("Basic dTpj", https.httpsPort())) {
            https.stubFor(post(urlEqualTo("/token")).willReturn(okJson(TOKEN_OK)));
            Path json = CuentaServicioDePrueba.escribir(dir, "https://localhost:" + https.httpsPort() + "/token");
            Path ca = Path.of(getClass().getResource("/tls/ca-prueba.pem").toURI());
            TokenCuentaServicio t = new TokenCuentaServicio(json,
                new ExtractorProperties.Proxy("localhost", tunel.puerto(), "u", "c", ca));
            assertThat(t.token()).isEqualTo("tok-1");
            assertThat(tunel.connectAutenticados.get()).isEqualTo(1);
            https.verify(1, postRequestedFor(urlEqualTo("/token")));
        } finally {
            https.stop();
        }
    }

    @Test
    void archivoInexistenteFallaAlCrear() {
        assertThatThrownBy(() -> new TokenCuentaServicio(dir.resolve("no-existe.json"), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EXTRACTOR_VERTEX_CREDENCIALES");
    }

    @Test
    void archivoNoServiceAccountFallaAlCrear() throws Exception {
        Path json = dir.resolve("usuario.json");
        Files.writeString(json, "{\"type\":\"authorized_user\",\"client_id\":\"a\",\"client_secret\":\"b\",\"refresh_token\":\"c\"}");
        assertThatThrownBy(() -> new TokenCuentaServicio(json, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EXTRACTOR_VERTEX_CREDENCIALES");
    }
}
