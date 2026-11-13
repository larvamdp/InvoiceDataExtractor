package ar.comprobantes.extractor.llm;

public interface LlmCliente {

    /** Devuelve el string JSON de {@code message.content}. */
    String completar(PromptLlm prompt);

    String modelo();
}
