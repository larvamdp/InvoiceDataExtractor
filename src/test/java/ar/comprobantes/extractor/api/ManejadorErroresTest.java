package ar.comprobantes.extractor.api;

import static org.assertj.core.api.Assertions.assertThat;

import ar.comprobantes.extractor.error.LlmCuotaAgotadaException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ManejadorErroresTest {

    private String detalle(DataSize max) {
        MultipartProperties props = new MultipartProperties();
        props.setMaxFileSize(max);
        ProblemDetail pd = new ManejadorErrores(props)
            .tamano(new MaxUploadSizeExceededException(max.toBytes())).getBody();
        return pd.getDetail();
    }

    @Test
    void mensaje413UsaElTamanoConfigurado() {
        assertThat(detalle(DataSize.ofMegabytes(25))).contains("25 MB").doesNotContain("10 MB");
        assertThat(detalle(DataSize.ofKilobytes(512))).contains("512 KB");
    }

    private static final String MENSAJE_CUOTA = "Se alcanzó el límite de uso del proveedor de IA.";

    @Test
    void cuotaAgotadaEs503ConRetryAfter() {
        var r = new ManejadorErrores(new MultipartProperties())
            .extraccion(new LlmCuotaAgotadaException(MENSAJE_CUOTA, 30L));
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(r.getBody().getDetail()).isEqualTo(MENSAJE_CUOTA);
    }

    @Test
    void cuotaAgotadaSinRetryAfterNoMandaHeader() {
        var r = new ManejadorErrores(new MultipartProperties())
            .extraccion(new LlmCuotaAgotadaException(MENSAJE_CUOTA, null));
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
    }
}
