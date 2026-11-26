package ar.comprobantes.extractor.llm.nube;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/** Genera un JSON de cuenta de servicio de Google con una clave RSA efímera, para pruebas. */
public final class CuentaServicioDePrueba {

    private CuentaServicioDePrueba() {
    }

    public static Path escribir(Path dir, String tokenUri) throws IOException {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair par = g.generateKeyPair();
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(par.getPrivate().getEncoded());
            String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
            String json = """
                {"type":"service_account","project_id":"proyecto-prueba","private_key_id":"kid-1",
                 "private_key":"%s","client_email":"extractor@proyecto-prueba.iam.gserviceaccount.com",
                 "client_id":"123","token_uri":"%s"}
                """.formatted(pem.replace("\n", "\\n"), tokenUri);
            Path archivo = dir.resolve("cuenta-servicio.json");
            Files.writeString(archivo, json);
            return archivo;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
