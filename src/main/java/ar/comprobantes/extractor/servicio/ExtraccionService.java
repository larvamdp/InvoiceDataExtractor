package ar.comprobantes.extractor.servicio;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.Fuente;
import ar.comprobantes.extractor.api.dto.Metadatos;
import ar.comprobantes.extractor.api.dto.ModoProcesamiento;
import ar.comprobantes.extractor.api.dto.QrArcaResultado;
import ar.comprobantes.extractor.api.dto.ResultadoExtraccion;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.api.dto.ValorCampo;
import ar.comprobantes.extractor.conciliacion.ConciliadorQr;
import ar.comprobantes.extractor.conciliacion.VerificadorTextual;
import ar.comprobantes.extractor.documento.DocumentoProcesado;
import ar.comprobantes.extractor.documento.ProcesadorDocumento;
import ar.comprobantes.extractor.error.RespuestaLlmInvalidaException;
import ar.comprobantes.extractor.llm.ConstructorPrompt;
import ar.comprobantes.extractor.llm.LlmCliente;
import ar.comprobantes.extractor.llm.ParserRespuestaLlm;
import ar.comprobantes.extractor.llm.PromptLlm;
import ar.comprobantes.extractor.llm.RespuestaLlm;
import ar.comprobantes.extractor.llm.ValorLlm;
import ar.comprobantes.extractor.qr.DatosQrArca;
import ar.comprobantes.extractor.qr.LectorQrArca;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExtraccionService {

    private static final Logger log = LoggerFactory.getLogger(ExtraccionService.class);
    private static final String NO_APARECE = "Valor no encontrado literalmente en el documento; verificar.";

    private final ProcesadorDocumento procesador;
    private final LectorQrArca lectorQr;
    private final ConstructorPrompt constructorPrompt;
    private final ParserRespuestaLlm parser;
    private final LlmCliente llm;
    private final LimitadorConcurrencia limitador;
    private final ConciliadorQr conciliador;
    private final VerificadorTextual verificador;

    public ExtraccionService(ProcesadorDocumento procesador, LectorQrArca lectorQr,
                             ConstructorPrompt constructorPrompt, ParserRespuestaLlm parser, LlmCliente llm,
                             LimitadorConcurrencia limitador, ConciliadorQr conciliador,
                             VerificadorTextual verificador) {
        this.procesador = procesador;
        this.lectorQr = lectorQr;
        this.constructorPrompt = constructorPrompt;
        this.parser = parser;
        this.llm = llm;
        this.limitador = limitador;
        this.conciliador = conciliador;
        this.verificador = verificador;
    }

    public ResultadoExtraccion extraer(byte[] documento, SolicitudExtraccion solicitud) {
        long inicio = System.nanoTime();
        DocumentoProcesado doc = procesador.procesar(documento);

        long t = System.nanoTime();
        QrArcaResultado qr = lectorQr.leer(doc.imagenesQr());
        long msQr = ms(t);

        PromptLlm prompt = constructorPrompt.construir(solicitud, doc, qr);

        long[] nanosLlm = new long[2]; // [0] tiempo dentro del LLM, [1] espera en cola
        RespuestaLlm respuesta = consultarLlm(prompt, solicitud, nanosLlm);

        DatosQrArca datosQr = qr.presente() ? qr.datos() : null;
        boolean verificarTexto = doc.modo() == ModoProcesamiento.TEXTO;
        String texto = verificarTexto ? doc.texto() : null;

        Map<String, ValorCampo> cabecera = new LinkedHashMap<>();
        for (CampoSolicitado campo : solicitud.camposCabecera()) {
            cabecera.put(campo.nombre(),
                conciliar(campo, respuesta.cabecera().get(campo.nombre()), datosQr, texto, verificarTexto));
        }
        List<Map<String, ValorCampo>> detalle = new ArrayList<>();
        for (Map<String, ValorLlm> fila : respuesta.detalle()) {
            Map<String, ValorCampo> salida = new LinkedHashMap<>();
            for (CampoSolicitado campo : solicitud.camposDetalle()) {
                salida.put(campo.nombre(), conciliar(campo, fila.get(campo.nombre()), null, texto, verificarTexto));
            }
            detalle.add(salida);
        }

        Map<String, Long> tiempos = new LinkedHashMap<>();
        tiempos.put("deteccion", doc.tiemposMs().getOrDefault("deteccion", 0L));
        tiempos.put("qr", msQr);
        tiempos.put("extraccionTexto", doc.tiemposMs().getOrDefault("extraccionTexto", 0L));
        tiempos.put("rasterizacion", doc.tiemposMs().getOrDefault("rasterizacion", 0L));
        tiempos.put("llm", nanosLlm[0] / 1_000_000);
        tiempos.put("esperaCola", nanosLlm[1] / 1_000_000);
        tiempos.put("total", ms(inicio));

        Metadatos metadatos = new Metadatos(doc.tipo().name(), doc.paginas(), doc.modo(), verificarTexto,
            llm.modelo(), tiempos);
        // Solo metadatos: nunca valores ni texto del documento.
        log.info("Extracción completada: tipo={} paginas={} modo={} qrPresente={} campos={} tiemposMs={}",
            doc.tipo(), doc.paginas(), doc.modo(), qr.presente(),
            solicitud.camposCabecera().size() + solicitud.camposDetalle().size(), tiempos);
        return new ResultadoExtraccion(cabecera, detalle, qr, metadatos);
    }

    private RespuestaLlm consultarLlm(PromptLlm prompt, SolicitudExtraccion solicitud, long[] nanos) {
        try {
            return parser.parsear(completar(prompt, nanos), solicitud);
        } catch (RespuestaLlmInvalidaException primera) {
            log.warn("Respuesta del modelo inválida; se reintenta una vez.");
            return parser.parsear(completar(prompt, nanos), solicitud);
        }
    }

    /** Acumula en nanos[0] el tiempo dentro de llm.completar y en nanos[1] la espera del limitador. */
    private String completar(PromptLlm prompt, long[] nanos) {
        return limitador.ejecutar(() -> {
            long desde = System.nanoTime();
            try {
                return llm.completar(prompt);
            } finally {
                nanos[0] += System.nanoTime() - desde;
            }
        }, espera -> nanos[1] += espera);
    }

    private ValorCampo conciliar(CampoSolicitado campo, ValorLlm valorLlm, DatosQrArca qr, String texto,
                                 boolean verificarTexto) {
        if (valorLlm == null) {
            return ValorCampo.noEncontrado();
        }
        ValorCampo vc = conciliador.conciliar(campo, valorLlm, qr);
        if (verificarTexto && vc.fuente() == Fuente.DOCUMENTO && vc.valor() != null
            && !verificador.aparece(valorLlm.valor(), vc.valor(), campo.tipo(), texto)) {
            String d = vc.descripcion();
            return new ValorCampo(vc.valor(), d == null || d.isBlank() ? NO_APARECE : d + " " + NO_APARECE,
                vc.fuente());
        }
        return vc;
    }

    private static long ms(long desdeNanos) {
        return (System.nanoTime() - desdeNanos) / 1_000_000;
    }
}
