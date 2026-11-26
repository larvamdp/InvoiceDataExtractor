package ar.comprobantes.extractor.llm.nube;

/** Entrega un token de acceso vigente para un proveedor en la nube. */
public interface ProveedorToken {

    /** Lanza {@code LlmNoDisponibleException} si no puede obtenerlo. */
    String token();
}
