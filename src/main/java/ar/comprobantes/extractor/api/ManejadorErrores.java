package ar.comprobantes.extractor.api;

import ar.comprobantes.extractor.error.ExtraccionException;
import jakarta.servlet.ServletException;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.util.unit.DataSize;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Los logs registran solo clase de excepción y status: nunca getMessage() de excepciones de terceros. */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    private final MultipartProperties multipart;

    public ManejadorErrores(MultipartProperties multipart) {
        this.multipart = multipart;
    }

    @ExceptionHandler(ExtraccionException.class)
    ResponseEntity<ProblemDetail> extraccion(ExtraccionException e) {
        return responder(e, problema(e.status(), e.titulo(), e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tamano(MaxUploadSizeExceededException e) {
        return responder(e, problema(HttpStatus.PAYLOAD_TOO_LARGE, "Documento demasiado grande",
            "El documento supera el tamaño máximo permitido de " + tamanoMaximo() + "."));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ProblemDetail> parteFaltante(MissingServletRequestPartException e) {
        return responder(e, problema(HttpStatus.BAD_REQUEST, "Solicitud inválida",
            "Falta la parte obligatoria '" + e.getRequestPartName() + "'."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> jsonInvalido(HttpMessageNotReadableException e) {
        return responder(e, problema(HttpStatus.BAD_REQUEST, "Solicitud inválida",
            "La parte 'solicitud' no es un JSON válido."));
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ProblemDetail> multipart(MultipartException e) {
        return responder(e, problema(HttpStatus.BAD_REQUEST, "Solicitud inválida",
            "La solicitud debe ser multipart/form-data con las partes 'documento' y 'solicitud'."));
    }

    /** Errores propios de Spring MVC (405, 415, 404, ...): conservan su status con un detalle genérico. */
    @ExceptionHandler({ServletException.class, ErrorResponseException.class})
    ResponseEntity<ProblemDetail> framework(Exception e) {
        if (!(e instanceof ErrorResponse er)) {
            return inesperado(e);
        }
        HttpStatusCode status = er.getStatusCode();
        String detalle;
        switch (status.value()) {
            case 404 -> detalle = "Recurso no encontrado.";
            case 405 -> detalle = "Método no permitido.";
            case 415 -> detalle = "Tipo de contenido no soportado.";
            default -> detalle = status.is4xxClientError() ? "Solicitud inválida." : "Error interno.";
        }
        String titulo = status.is4xxClientError() ? "Solicitud inválida" : "Error interno";
        return responder(e, problema(status, titulo, detalle));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception e) {
        // Sin mensajes ni throwable: solo cadena de clases y primeros frames de la causa raíz.
        StringBuilder cadena = new StringBuilder();
        Throwable raiz = e;
        for (Throwable t = e; t != null && cadena.length() < 500; t = t.getCause()) {
            if (cadena.length() > 0) {
                cadena.append(" <- ");
            }
            cadena.append(t.getClass().getSimpleName());
            raiz = t;
        }
        StringBuilder frames = new StringBuilder();
        StackTraceElement[] st = raiz.getStackTrace();
        for (int i = 0; i < Math.min(5, st.length); i++) {
            frames.append("\n  ").append(st[i].getClassName()).append('.').append(st[i].getMethodName())
                .append(':').append(st[i].getLineNumber());
        }
        log.error("Error interno inesperado: causas={} frames:{}", cadena, frames);
        return ResponseEntity.status(500).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problema(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno", "Error interno."));
    }

    private String tamanoMaximo() {
        DataSize max = multipart.getMaxFileSize();
        if (max == null) {
            return "el configurado";
        }
        long bytes = max.toBytes();
        long mb = 1024L * 1024L;
        return bytes % mb == 0 ? (bytes / mb) + " MB" : bytes % 1024 == 0 ? (bytes / 1024) + " KB" : bytes + " bytes";
    }

    private static ProblemDetail problema(HttpStatusCode status, String titulo, String detalle) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detalle);
        pd.setTitle(titulo);
        return pd;
    }

    private static ResponseEntity<ProblemDetail> responder(Exception e, ProblemDetail pd) {
        log.warn("Solicitud rechazada: excepcion={} status={}", e.getClass().getSimpleName(), pd.getStatus());
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
