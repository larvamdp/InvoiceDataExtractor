package ar.comprobantes.extractor.qr;

import ar.comprobantes.extractor.api.dto.QrArcaResultado;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class LectorQrArca {

    private final DecodificadorQrArca decodificador;

    public LectorQrArca(DecodificadorQrArca decodificador) {
        this.decodificador = decodificador;
    }

    public QrArcaResultado leer(List<BufferedImage> paginas) {
        boolean hayQr = false;
        for (BufferedImage pagina : paginas) {
            for (Result r : decodificarPagina(pagina)) {
                hayQr = true;
                Optional<DatosQrArca> datos = decodificador.decodificar(r.getText());
                if (datos.isPresent()) {
                    return new QrArcaResultado(true, datos.get(), null);
                }
            }
        }
        if (hayQr) {
            return new QrArcaResultado(false, null,
                    "Se encontró un código QR pero no corresponde a ARCA o no pudo decodificarse.");
        }
        return new QrArcaResultado(false, null, "No se encontró código QR de ARCA.");
    }

    private List<Result> decodificarPagina(BufferedImage imagen) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        MultiFormatReader lector = new MultiFormatReader();
        lector.setHints(hints);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(imagen)));
        try {
            return List.of(new GenericMultipleBarcodeReader(lector).decodeMultiple(bitmap, hints));
        } catch (NotFoundException e) {
            return List.of();
        }
    }
}
