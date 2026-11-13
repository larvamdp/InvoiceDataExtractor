package ar.comprobantes.extractor.servicio;

import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.error.ServicioOcupadoException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Limita la cantidad de llamadas simultáneas al LLM; las demás esperan hasta un máximo configurable. */
@Component
public class LimitadorConcurrencia {

    private static final String MENSAJE = "El servicio está procesando otros documentos; reintente más tarde.";

    private final Semaphore semaforo;
    private final long esperaNanos;

    public LimitadorConcurrencia(ExtractorProperties propiedades) {
        ExtractorProperties.Concurrencia c = propiedades.concurrencia();
        this.semaforo = new Semaphore(c.maxLlm(), true);
        this.esperaNanos = c.esperaMaxima().toNanos();
    }

    public <T> T ejecutar(Supplier<T> tarea) {
        boolean adquirido;
        try {
            adquirido = semaforo.tryAcquire(esperaNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServicioOcupadoException(MENSAJE, e);
        }
        if (!adquirido) {
            throw new ServicioOcupadoException(MENSAJE);
        }
        try {
            return tarea.get();
        } finally {
            semaforo.release();
        }
    }
}
