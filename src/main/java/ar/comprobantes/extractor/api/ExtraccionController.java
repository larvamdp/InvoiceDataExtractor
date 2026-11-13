package ar.comprobantes.extractor.api;

import ar.comprobantes.extractor.api.dto.ResultadoExtraccion;
import ar.comprobantes.extractor.api.dto.SolicitudExtraccion;
import ar.comprobantes.extractor.error.DocumentoInvalidoException;
import ar.comprobantes.extractor.servicio.ExtraccionService;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/extracciones")
public class ExtraccionController {

    private final ExtraccionService servicio;
    private final ValidadorSolicitud validador;

    public ExtraccionController(ExtraccionService servicio, ValidadorSolicitud validador) {
        this.servicio = servicio;
        this.validador = validador;
    }

    @PostMapping(path = "/sync", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResultadoExtraccion extraerSync(@RequestPart("documento") MultipartFile documento,
                                           @RequestPart("solicitud") SolicitudExtraccion solicitud) {
        validador.validar(solicitud);
        byte[] bytes;
        try {
            bytes = documento.getBytes();
        } catch (IOException e) {
            throw new DocumentoInvalidoException("No se pudo leer el documento recibido.");
        }
        return servicio.extraer(bytes, solicitud);
    }
}
