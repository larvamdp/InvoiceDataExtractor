package ar.comprobantes.extractor.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.comprobantes.extractor.api.dto.CampoSolicitado;
import ar.comprobantes.extractor.api.dto.Fuente;
import ar.comprobantes.extractor.api.dto.ModoProcesamiento;
import ar.comprobantes.extractor.api.dto.ResultadoExtraccion;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.api.dto.TipoCampo;
import ar.comprobantes.extractor.api.dto.ValorCampo;
import ar.comprobantes.extractor.conciliacion.ConciliadorQr;
import ar.comprobantes.extractor.conciliacion.VerificadorTextual;
import ar.comprobantes.extractor.config.ExtractorProperties;
import ar.comprobantes.extractor.documento.DetectorTipoDocumento;
import ar.comprobantes.extractor.documento.ProcesadorDocumento;
import ar.comprobantes.extractor.error.LlmLimiteTokensException;
import ar.comprobantes.extractor.error.RespuestaLlmInvalidaException;
import ar.comprobantes.extractor.llm.ConstructorPrompt;
import ar.comprobantes.extractor.llm.EsquemaRespuesta;
import ar.comprobantes.extractor.llm.LlmCliente;
import ar.comprobantes.extractor.llm.ParserRespuestaLlm;
import ar.comprobantes.extractor.llm.PromptLlm;
import ar.comprobantes.extractor.normalizacion.Normalizador;
import ar.comprobantes.extractor.qr.DecodificadorQrArca;
import ar.comprobantes.extractor.qr.LectorQrArca;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class ExtraccionServiceTest {

    private static final String QR_JSON = "{\"ver\":1,\"fecha\":\"2025-03-15\",\"cuit\":30712345671,\"ptoVta\":3,"
        + "\"tipoCmp\":1,\"nroCmp\":12345,\"importe\":15000.0,\"moneda\":\"PES\",\"ctz\":1,\"tipoDocRec\":80,"
        + "\"nroDocRec\":20111111112,\"tipoCodAut\":\"E\",\"codAut\":70123456789012}";

    private static final String JSON_OK = """
        {"cabecera":{"importeTotal":"15.000,00","numero":"0003-00012345"},
         "detalle":[{"descripcion":"Servicio de consultoria","importe":"1.234,56"},
                    {"descripcion":"Servicio de consultoria","importe":"2.000,00"}],
         "clavesQr":{"importeTotal":["importe"]}}
        """;

    private final AtomicInteger llamadas = new AtomicInteger();

    private ExtraccionService servicio(Function<Integer, String> respuestas) {
        return servicio(respuestas, null);
    }

    private static final ExtractorProperties PROPS = new ExtractorProperties(null,
        new ExtractorProperties.Concurrencia(1, Duration.ofSeconds(5)),
        new ExtractorProperties.Documento(10, 150, 200, 2000, 50), null);

    private ExtraccionService servicio(Function<Integer, String> respuestas, LimitadorConcurrencia limite) {
        ExtractorProperties props = PROPS;
        ObjectMapper om = new ObjectMapper();
        Normalizador norm = new Normalizador();
        LlmCliente llm = new LlmCliente() {
            @Override
            public String completar(PromptLlm prompt) {
                return respuestas.apply(llamadas.incrementAndGet());
            }

            @Override
            public String modelo() {
                return "modelo-falso";
            }
        };
        return new ExtraccionService(
            new ProcesadorDocumento(new DetectorTipoDocumento(), props),
            new LectorQrArca(new DecodificadorQrArca()),
            new ConstructorPrompt(new EsquemaRespuesta(), om),
            new ParserRespuestaLlm(om), llm, limite != null ? limite : new LimitadorConcurrencia(props),
            new ConciliadorQr(norm), new VerificadorTextual(norm));
    }

    private static SolicitudExtraccion solicitud() {
        return new SolicitudExtraccion(
            List.of(new CampoSolicitado("importeTotal", List.of("Total"), null, TipoCampo.IMPORTE),
                new CampoSolicitado("numero", List.of("Factura"), null, TipoCampo.TEXTO)),
            List.of(new CampoSolicitado("descripcion", List.of(), null, TipoCampo.TEXTO),
                new CampoSolicitado("importe", List.of(), null, TipoCampo.IMPORTE)));
    }

    @Test
    void flujoTextoConQr() throws Exception {
        ResultadoExtraccion r = servicio(n -> JSON_OK).extraer(pdf(true), solicitud());
        assertThat(r.cabecera().get("importeTotal").fuente()).isEqualTo(Fuente.QR_ARCA);
        assertThat(r.cabecera().get("numero").fuente()).isEqualTo(Fuente.DOCUMENTO);
        assertThat(r.cabecera().get("numero").descripcion()).isNull();
        assertThat(r.qrArca().presente()).isTrue();
        assertThat(r.metadatos().modoProcesamiento()).isEqualTo(ModoProcesamiento.TEXTO);
        assertThat(r.metadatos().tipoDocumento()).isEqualTo("PDF");
        assertThat(r.metadatos().verificacionTextual()).isTrue();
        assertThat(r.metadatos().modelo()).isEqualTo("modelo-falso");
        assertThat(r.metadatos().tiemposMs().keySet()).containsExactly(
            "deteccion", "qr", "extraccionTexto", "rasterizacion", "llm", "esperaCola", "total");
        assertThat(r.metadatos().tiemposMs().get("total")).isGreaterThanOrEqualTo(r.metadatos().tiemposMs().get("llm"));
    }

    @Test
    void tiempoLlmExcluyeEsperaEnCola() throws Exception {
        CountDownLatch intentando = new CountDownLatch(1);
        CountDownLatch dentro = new CountDownLatch(1);
        LimitadorConcurrencia limite = new LimitadorConcurrencia(PROPS) {
            @Override
            public <T> T ejecutar(java.util.function.Supplier<T> tarea, java.util.function.LongConsumer espera) {
                if (dentro.getCount() == 0) { // la llamada del ocupante no cuenta
                    intentando.countDown();
                }
                return super.ejecutar(tarea, espera);
            }
        };
        CountDownLatch soltar = new CountDownLatch(1);
        Thread ocupante = new Thread(() -> limite.ejecutar(() -> {
            dentro.countDown();
            try {
                soltar.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "x";
        }));
        ocupante.start();
        dentro.await();
        // El permiso se libera 300 ms después de que el servicio empieza a esperar en el limitador.
        Thread liberador = new Thread(() -> {
            try {
                intentando.await();
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            soltar.countDown();
        });
        liberador.start();
        byte[] pdf = pdf(true);
        ResultadoExtraccion r = servicio(n -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return JSON_OK;
        }, limite).extraer(pdf, solicitud());
        ocupante.join();
        liberador.join();
        var t = r.metadatos().tiemposMs();
        assertThat(t.get("esperaCola")).isGreaterThanOrEqualTo(250L);
        assertThat(t.get("llm")).isLessThan(t.get("esperaCola"));
        assertThat(t.get("total")).isGreaterThanOrEqualTo(t.get("esperaCola") + t.get("llm"));
    }

    @Test
    void valorInventadoSeMarca() throws Exception {
        String json = """
            {"cabecera":{"importeTotal":"15.000,00","numero":"Energía reactiva"},
             "detalle":[]}
            """;
        ResultadoExtraccion r = servicio(n -> json).extraer(pdf(false), solicitud());
        assertThat(r.cabecera().get("numero").valor()).isEqualTo("Energía reactiva");
        assertThat(r.cabecera().get("numero").descripcion()).contains("verificar");
    }

    @Test
    void reintentaUnaVezSiJsonInvalido() throws Exception {
        ResultadoExtraccion r = servicio(n -> n == 1 ? "no json" : JSON_OK).extraer(pdf(true), solicitud());
        assertThat(llamadas.get()).isEqualTo(2);
        assertThat(r.cabecera()).containsKey("importeTotal");
    }

    @Test
    void noReintentaSiSeAlcanzaElLimite() throws Exception {
        ExtraccionService s = servicio(n -> {
            throw new LlmLimiteTokensException("límite");
        });
        byte[] pdf = pdf(true);
        assertThatThrownBy(() -> s.extraer(pdf, solicitud())).isInstanceOf(LlmLimiteTokensException.class);
        assertThat(llamadas.get()).isEqualTo(1);
    }

    @Test
    void fallaTrasDosJsonInvalidos() throws Exception {
        ExtraccionService s = servicio(n -> "no json");
        byte[] pdf = pdf(true);
        assertThatThrownBy(() -> s.extraer(pdf, solicitud())).isInstanceOf(RespuestaLlmInvalidaException.class);
        assertThat(llamadas.get()).isEqualTo(2);
    }

    @Test
    void modoImagenSinVerificacion() throws Exception {
        String json = """
            {"cabecera":{"importeTotal":"15.000,00","numero":"Energía reactiva"},
             "detalle":[]}
            """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "png", out);
        ResultadoExtraccion r = servicio(n -> json).extraer(out.toByteArray(), solicitud());
        assertThat(r.metadatos().verificacionTextual()).isFalse();
        assertThat(r.metadatos().modoProcesamiento()).isEqualTo(ModoProcesamiento.IMAGEN);
        assertThat(r.metadatos().tipoDocumento()).isEqualTo("PNG");
        r.cabecera().values().forEach(v -> assertThat(String.valueOf(v.descripcion())).doesNotContain("verificar"));
    }

    @Test
    void detalleSeConciliaFilaPorFila() throws Exception {
        ResultadoExtraccion r = servicio(n -> JSON_OK).extraer(pdf(true), solicitud());
        assertThat(r.detalle()).hasSize(2);
        ValorCampo f1 = r.detalle().get(0).get("importe");
        ValorCampo f2 = r.detalle().get(1).get("importe");
        assertThat((BigDecimal) f1.valor()).isEqualByComparingTo("1234.56");
        assertThat((BigDecimal) f2.valor()).isEqualByComparingTo("2000.00");
    }

    /** PDF con texto de factura; opcionalmente con el QR de ARCA como imagen. */
    private static byte[] pdf(boolean conQr) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage pagina = new PDPage(PDRectangle.A4);
            doc.addPage(pagina);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 750);
                for (String l : List.of(
                    "Factura 0003-00012345 de Razon Social de ejemplo sociedad anonima",
                    "Servicio de consultoria 1.234,56",
                    "Servicio de consultoria 2.000,00",
                    "Total 15.000,00")) {
                    cs.showText(l);
                    cs.newLineAtOffset(0, -20);
                }
                cs.endText();
                if (conQr) {
                    String url = "https://www.afip.gob.ar/fe/qr/?p=" + Base64.getEncoder()
                        .encodeToString(QR_JSON.getBytes(StandardCharsets.UTF_8));
                    BufferedImage qr = MatrixToImageWriter.toBufferedImage(
                        new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 400, 400));
                    PDImageXObject x = LosslessFactory.createFromImage(doc, qr);
                    cs.drawImage(x, 350, 50, 200, 200);
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
