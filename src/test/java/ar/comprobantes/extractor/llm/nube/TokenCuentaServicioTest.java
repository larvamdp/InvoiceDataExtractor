package ar.comprobantes.extractor.llm.nube;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.Authenticator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertThatThrownBy(() -> crear().token()).isInstanceOf(LlmNoDisponibleException.class);
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
