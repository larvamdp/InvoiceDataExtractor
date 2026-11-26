package ar.comprobantes.extractor.config;

/** Proveedor de IA usado para extraer los campos del comprobante. */
public enum ProveedorLlm {
    OLLAMA,
    AZURE_OPENAI,
    VERTEX_GEMINI
}
