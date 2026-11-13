package ar.comprobantes.extractor.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.ServicioOcupadoException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class LimitadorConcurrenciaTest {

    private static LimitadorConcurrencia limitador(int max, Duration espera) {
        return new LimitadorConcurrencia(new ExtractorProperties(null,
            new ExtractorProperties.Concurrencia(max, espera), null, null));
    }

    @Test
    void limitadorRechazaCuandoEstaOcupado() throws Exception {
        LimitadorConcurrencia l = limitador(1, Duration.ofMillis(100));
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            ex.submit(() -> l.ejecutar(() -> {
                dentro.countDown();
                try {
                    soltar.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "x";
            }));
            dentro.await();
            assertThatThrownBy(() -> l.ejecutar(() -> "y"))
                .isInstanceOf(ServicioOcupadoException.class)
                .hasMessage("El servicio está procesando otros documentos; reintente más tarde.");
        } finally {
            soltar.countDown();
            ex.shutdown();
        }
    }

    @Test
    void limitadorLiberaTrasExcepcion() {
        LimitadorConcurrencia l = limitador(1, Duration.ofMillis(100));
        assertThatThrownBy(() -> l.ejecutar(() -> {
            throw new IllegalStateException("falla");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(l.ejecutar(() -> "ok")).isEqualTo("ok");
    }
}
