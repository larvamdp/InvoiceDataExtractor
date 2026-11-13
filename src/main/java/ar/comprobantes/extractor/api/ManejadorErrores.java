package ar.comprobantes.extractor.api;

import ar.comprobantes.extractor.error.ExtraccionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(ExtraccionException.class)
    ResponseEntity<ProblemDetail> extraccion(ExtraccionException e) {
        return responder(e, problema(e.status(), e.titulo(), e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tamano(MaxUploadSizeExceededException e) {
        return responder(e, problema(HttpStatus.PAYLOAD_TOO_LARGE, "Documento demasiado grande",
            "El documento supera el tamaño máximo permitido de 10 MB."));
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

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception e) {
        return responder(e, problema(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno", "Error interno."));
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
