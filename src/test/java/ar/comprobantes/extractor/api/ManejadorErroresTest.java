package ar.comprobantes.extractor.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
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
}
