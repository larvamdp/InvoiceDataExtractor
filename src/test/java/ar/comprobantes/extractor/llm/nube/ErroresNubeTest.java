package ar.comprobantes.extractor.llm.nube;

import static org.assertj.core.api.Assertions.assertThat;

import ar.comprobantes.extractor.error.ExtraccionException;
import ar.comprobantes.extractor.error.LlmCuotaAgotadaException;
import ar.comprobantes.extractor.error.LlmNoDisponibleException;
import ar.comprobantes.extractor.error.LlmTimeoutException;
import ar.comprobantes.extractor.error.RespuestaLlmInvalidaException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.http.HttpStatusCode;

@ExtendWith(OutputCaptureExtension.class)
class ErroresNubeTest {

    private static final String MSG = "El servicio de IA no está disponible.";

    private static HttpClientErrorException http(int status, HttpHeaders h, String cuerpo) {
        return HttpClientErrorException.create(HttpStatusCode.valueOf(status), "x", h,
            cuerpo.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static HttpHeaders retryAfter(String v) {
        HttpHeaders h = new HttpHeaders();
        h.add("Retry-After", v);
        return h;
    }

    private static ExtraccionException traducir(Exception e) {
        return ErroresNube.traducir(e, "azure", "gpt", false);
    }

    @Test
    void cuota429ConSegundos() {
        ExtraccionException r = traducir(http(429, retryAfter("30"), ""));
        assertThat(r).isInstanceOf(LlmCuotaAgotadaException.class)
            .hasMessage("Se alcanzó el límite de uso del proveedor de IA.");
        assertThat(((LlmCuotaAgotadaException) r).retryAfterSegundos()).isEqualTo(30L);
    }

    @Test
    void retryAfterFechaHttp() {
        String fecha = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(120));
        Long s = ((LlmCuotaAgotadaException) traducir(http(429, retryAfter(fecha), ""))).retryAfterSegundos();
        assertThat(s).isBetween(100L, 121L);
    }

    @Test
    void retryAfterPasadoONegativoEsCero() {
        String fecha = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).minusHours(1));
        assertThat(((LlmCuotaAgotadaException) traducir(http(429, retryAfter(fecha), ""))).retryAfterSegundos())
            .isZero();
        assertThat(((LlmCuotaAgotadaException) traducir(http(429, retryAfter("-5"), ""))).retryAfterSegundos())
            .isZero();
    }

    @Test
    void retryAfterIlegibleEsNull() {
        assertThat(((LlmCuotaAgotadaException) traducir(http(429, retryAfter("pronto"), ""))).retryAfterSegundos())
            .isNull();
        assertThat(((LlmCuotaAgotadaException) traducir(http(429, new HttpHeaders(), ""))).retryAfterSegundos())
            .isNull();
    }

    @Test
    void credencialesRechazadas(CapturedOutput out) {
        for (int status : new int[] {401, 403}) {
            ExtraccionException r = traducir(http(status, new HttpHeaders(), ""));
            assertThat(r).isInstanceOf(LlmNoDisponibleException.class).hasMessage(MSG);
        }
        assertThat(out.getAll()).contains("credenciales rechazadas por el proveedor: proveedor=azure status=401");
    }

    @Test
    void proxyRechazaAutenticacion(CapturedOutput out) {
        assertThat(traducir(http(407, new HttpHeaders(), ""))).isInstanceOf(LlmNoDisponibleException.class)
            .hasMessage(MSG);
        assertThat(out.getAll()).contains("el proxy rechazó la autenticación");
    }

    @Test
    void otroStatusLoguea(CapturedOutput out) {
        assertThat(traducir(http(500, new HttpHeaders(), "{\"error\":{\"message\":\"SECRETO\"}}")))
            .isInstanceOf(LlmNoDisponibleException.class).hasMessage(MSG);
        assertThat(out.getAll()).contains("HTTP 500").doesNotContain("SECRETO");
    }

    @Test
    void error400LogueaCodeNoMessage(CapturedOutput out) {
        ExtraccionException r = traducir(http(400, new HttpHeaders(),
            "{\"error\":{\"code\":\"invalid_parameter\",\"message\":\"SECRETO\"}}"));
        assertThat(r).isInstanceOf(LlmNoDisponibleException.class).hasMessage(MSG);
        assertThat(out.getAll()).contains("invalid_parameter").doesNotContain("SECRETO");
    }

    @Test
    void error400SinJsonNoFalla() {
        assertThat(traducir(http(400, new HttpHeaders(), "<html>"))).isInstanceOf(LlmNoDisponibleException.class);
    }

    @Test
    void contentFilterEs422() {
        ExtraccionException r = traducir(http(400, new HttpHeaders(),
            "{\"error\":{\"code\":\"content_filter\",\"message\":\"SECRETO\"}}"));
        assertThat(r).isInstanceOf(RespuestaLlmInvalidaException.class)
            .hasMessage("El proveedor de IA bloqueó el contenido.");
        assertThat(r.status().value()).isEqualTo(422);
    }

    @Test
    void timeoutDeLectura() {
        ExtraccionException r = traducir(new ResourceAccessException("x", new HttpTimeoutException("t")));
        assertThat(r).isInstanceOf(LlmTimeoutException.class)
            .hasMessage("El modelo excedió el tiempo máximo de respuesta.");
        assertThat(ErroresNube.esTimeout(new RuntimeException(new SocketTimeoutException()))).isTrue();
    }

    @Test
    void timeoutDeConexionNoEsTimeoutDeLectura() {
        assertThat(traducir(new ResourceAccessException("x", new HttpConnectTimeoutException("t"))))
            .isInstanceOf(LlmNoDisponibleException.class);
    }

    @Test
    void sinConexion(CapturedOutput out) {
        ResourceAccessException e = new ResourceAccessException("SECRETO");
        assertThat(ErroresNube.traducir(e, "azure", "gpt", false)).isInstanceOf(LlmNoDisponibleException.class)
            .hasMessage(MSG);
        assertThat(out.getAll()).contains("no se pudo conectar con el proveedor").doesNotContain("SECRETO");
        assertThat(ErroresNube.traducir(e, "azure", "gpt", true)).isInstanceOf(LlmNoDisponibleException.class);
        assertThat(out.getAll()).contains("no se pudo conectar (vía proxy)");
    }

    @Test
    void otraRestClientExceptionLogueaSoloClase(CapturedOutput out) {
        assertThat(traducir(new RestClientException("SECRETO"))).isInstanceOf(LlmNoDisponibleException.class)
            .hasMessage(MSG);
        assertThat(out.getAll()).contains("RestClientException").doesNotContain("SECRETO");
    }
}
