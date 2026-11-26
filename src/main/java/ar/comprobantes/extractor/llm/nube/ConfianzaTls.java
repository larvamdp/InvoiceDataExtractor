package ar.comprobantes.extractor.llm.nube;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Confianza TLS: CA del JDK más las CA de un PEM extra (proxy corporativo con inspección TLS). */
public final class ConfianzaTls {

    private static final String MSG_CA_EXTRA = "EXTRACTOR_PROXY_CAEXTRA: no se pudo leer el certificado";

    private ConfianzaTls() {
    }

    public static SSLContext contexto(Path pemExtra) {
        try {
            if (pemExtra == null) {
                return SSLContext.getDefault();
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore(pemExtra));
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(MSG_CA_EXTRA);
        }
    }

    public static KeyStore keyStore(Path pemExtra) {
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof javax.net.ssl.X509TrustManager x509) {
                    for (X509Certificate ca : x509.getAcceptedIssuers()) {
                        ks.setCertificateEntry("jdk-" + i++, ca);
                    }
                }
            }
            if (pemExtra != null) {
                Collection<? extends Certificate> extra;
                try (InputStream in = Files.newInputStream(pemExtra)) {
                    extra = CertificateFactory.getInstance("X.509").generateCertificates(in);
                }
                if (extra.isEmpty()) {
                    throw new IllegalStateException(MSG_CA_EXTRA);
                }
                int j = 0;
                for (Certificate c : extra) {
                    ks.setCertificateEntry("extra-" + j++, c);
                }
            }
            return ks;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(MSG_CA_EXTRA);
        }
    }
}
