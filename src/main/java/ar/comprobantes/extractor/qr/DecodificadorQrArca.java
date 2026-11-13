package ar.comprobantes.extractor.qr;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

@Component
public class DecodificadorQrArca {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public Optional<DatosQrArca> decodificar(String contenidoQr) {
        if (contenidoQr == null) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(contenidoQr.trim());
            String host = uri.getHost();
            if (host == null || !(esDominio(host, "afip.gob.ar") || esDominio(host, "arca.gob.ar"))) {
                return Optional.empty();
            }
            if (uri.getPath() == null || !uri.getPath().contains("/fe/qr")) {
                return Optional.empty();
            }
            String p = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("p");
            if (p == null || p.isBlank()) {
                return Optional.empty();
            }
            String normalizado = p.replace('-', '+').replace('_', '/');
            byte[] bytes = Base64.getMimeDecoder().decode(normalizado);
            String json = new String(bytes, StandardCharsets.UTF_8);
            return Optional.ofNullable(mapper.readValue(json, DatosQrArca.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean esDominio(String host, String dominio) {
        String h = host.toLowerCase();
        return h.equals(dominio) || h.endsWith("." + dominio);
    }
}
