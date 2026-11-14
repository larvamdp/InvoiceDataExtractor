package ar.comprobantes.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import ar.comprobantes.extractor.llm.LlmCliente;
import ar.comprobantes.extractor.llm.OllamaLlmCliente;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Arranca el contexto completo con los beans reales (sin mocks); no hace llamadas de red al construirse. */
@SpringBootTest
class ContextoCompletoTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void elContextoArrancaConElClienteOllamaReal() {
        assertThat(ctx.getBean(LlmCliente.class)).isInstanceOf(OllamaLlmCliente.class);
    }
}
