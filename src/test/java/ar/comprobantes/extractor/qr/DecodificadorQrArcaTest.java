package ar.comprobantes.extractor.qr;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class DecodificadorQrArcaTest {

    static final String JSON = "{\"ver\":1,\"fecha\":\"2025-03-15\",\"cuit\":30712345671,\"ptoVta\":3,\"tipoCmp\":1,"
            + "\"nroCmp\":12345,\"importe\":15000.0,\"moneda\":\"PES\",\"ctz\":1,\"tipoDocRec\":80,"
            + "\"nroDocRec\":20111111112,\"tipoCodAut\":\"E\",\"codAut\":70123456789012,\"extra\":\"x\"}";

    static String urlQr(String json) {
        return "https://www.afip.gob.ar/fe/qr/?p="
                + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private final DecodificadorQrArca decodificador = new DecodificadorQrArca();

    @Test
    void decodificaUrlAfip() {
        DatosQrArca d = decodificador.decodificar(urlQr(JSON)).orElseThrow();
        assertThat(d.cuit()).isEqualTo(30712345671L);
        assertThat(d.ptoVta()).isEqualTo(3);
        assertThat(d.nroCmp()).isEqualTo(12345L);
        assertThat(d.importe()).isEqualByComparingTo(new BigDecimal("15000.0"));
    }

    @Test
    void aceptaDominioArcaYBase64UrlSafeSinPadding() {
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.getBytes(StandardCharsets.UTF_8));
        assertThat(decodificador.decodificar("https://servicioscf.arca.gob.ar/fe/qr/?p=" + p)).isPresent();
    }

    @Test
    void rechazaOtroDominio() {
        assertThat(decodificador.decodificar(urlQr(JSON).replace("www.afip.gob.ar", "ejemplo.com"))).isEmpty();
        assertThat(decodificador.decodificar("https://afip.gob.ar.evil.com/fe/qr/?p=e30=")).isEmpty();
    }

    @Test
    void rechazaBase64Invalido() {
        assertThat(decodificador.decodificar("https://www.afip.gob.ar/fe/qr/?p=@@@no-es-base64@@@")).isEmpty();
        assertThat(decodificador.decodificar("hola")).isEmpty();
        assertThat(decodificador.decodificar(null)).isEmpty();
    }

    @Test
    void valorDeClave() {
        DatosQrArca d = decodificador.decodificar(urlQr(JSON)).orElseThrow();
        assertThat(d.valorDeClave("nroCmp")).isEqualTo(12345L);
        assertThat(DatosQrArca.CLAVES).hasSize(13);
    }
}
