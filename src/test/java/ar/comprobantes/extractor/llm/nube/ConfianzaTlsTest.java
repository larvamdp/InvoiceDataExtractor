package ar.comprobantes.extractor.llm.nube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfianzaTlsTest {

    private static final Path CA = pemDePrueba();

    private static Path pemDePrueba() {
        try {
            return Path.of(ConfianzaTlsTest.class.getResource("/tls/ca-prueba.pem").toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void agregaCaDelPem() throws Exception {
        Certificate esperado;
        try (InputStream in = Files.newInputStream(CA)) {
            esperado = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore ks = ConfianzaTls.keyStore(CA);
        assertThat(Collections.list(ks.aliases()).stream().anyMatch(a -> {
            try {
                return esperado.equals(ks.getCertificate(a));
            } catch (Exception e) {
                return false;
            }
        })).isTrue();
        assertThat(ks.size()).isGreaterThan(1);
        assertThat(ConfianzaTls.contexto(CA)).isNotNull();
    }

    @Test
    void sinPemUsaContextoPorDefecto() throws Exception {
        assertThat(ConfianzaTls.contexto(null)).isSameAs(javax.net.ssl.SSLContext.getDefault());
    }

    @Test
    void pemInvalidoFalla(@TempDir Path dir) throws Exception {
        Path malo = Files.writeString(dir.resolve("malo.pem"), "esto no es un certificado");
        assertThatThrownBy(() -> ConfianzaTls.keyStore(malo))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("EXTRACTOR_PROXY_CAEXTRA");
        assertThatThrownBy(() -> ConfianzaTls.contexto(malo))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("EXTRACTOR_PROXY_CAEXTRA");
    }
}
