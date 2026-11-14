# InvoiceDataExtractor

Servicio REST (Spring Boot 3.5 / Java 21) que extrae campos de facturas argentinas (PDF, JPG, PNG) con un LLM local servido por Ollama. Camino híbrido: lectura del QR de ARCA, texto del PDF cuando existe e imagen cuando no. Ningún dato sale de tu infraestructura.

## Requisitos

- Docker con Compose v2 (`docker compose`).
- Para GPU: NVIDIA Container Toolkit. Sin GPU funciona en CPU, más lento.
- Sin GPU (solo CPU) el modelo es muy lento: un PDF de texto de 1 página tardó ~7 minutos en una notebook. Con el timeout por defecto (`90s`) la API responde 504; subí `EXTRACTOR_OLLAMA_TIMEOUT` (p. ej. `600s`, en un archivo `.env` junto al compose o como variable de entorno).
- Espacio en disco: el modelo por defecto (`qwen3-vl:4b`, licencia Apache 2.0) ocupa ~3.3 GB.

## Puesta en marcha

1. Descargar el modelo (único paso con acceso a internet):

   ```bash
   ./scripts/descargar-modelo.sh qwen3-vl:4b
   ```

2. Levantar los servicios:

   ```bash
   docker compose up -d --build
   ```

   Variante con GPU NVIDIA:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build
   ```

El contenedor `ollama` está solo en la red interna `ia` (sin salida a internet ni puertos publicados). Solo `extractor-api` expone el puerto 8080.

## Uso

```bash
curl -sS --max-time 600 \
  -F "documento=@factura.pdf" \
  -F "solicitud=@ejemplos/solicitud.json;type=application/json" \
  http://localhost:8080/api/v1/extracciones/sync
```

`ejemplos/solicitud.json` define los campos de cabecera y de detalle (nombre, etiquetas, descripción, tipo `TEXTO | NUMERO | IMPORTE | FECHA | CUIT`). Cada campo vuelve con `valor`, `descripcion` y `fuente` (`QR_ARCA | DOCUMENTO | null`). Dato ausente: `valor: null` y `"Dato no encontrado."`.

Errores en formato RFC 7807: 400 inválido, 413 tamaño/páginas, 415 formato, 422 JSON del LLM inválido, 503 ocupado u Ollama caído, 504 timeout del LLM.

### Tiempos (`metadatos.tiemposMs`)

| Clave | Qué mide |
|---|---|
| `deteccion` | Detección del tipo de documento |
| `qr` | Búsqueda y decodificación del QR de ARCA |
| `extraccionTexto` | Extracción de texto del PDF |
| `rasterizacion` | Conversión de páginas a imagen |
| `llm` | Llamada al modelo |
| `esperaCola` | Espera del permiso de concurrencia del LLM |
| `total` | Tiempo total de la solicitud |

## Variables de entorno

Toda la configuración está bajo `extractor.*` y se puede sobreescribir con variables de entorno (binding relajado).

| Variable | Propiedad | Default | Descripción |
|---|---|---|---|
| `EXTRACTOR_OLLAMA_URL` | `extractor.ollama.url` | `http://localhost:11434` (compose: `http://ollama:11434`) | URL de Ollama |
| `EXTRACTOR_OLLAMA_MODELO` | `extractor.ollama.modelo` | `qwen3-vl:4b` | Modelo |
| `EXTRACTOR_OLLAMA_TIMEOUT` | `extractor.ollama.timeout` | `90s` | Timeout de la llamada al LLM |
| `EXTRACTOR_OLLAMA_NUMCTX` | `extractor.ollama.num-ctx` | `8192` | Contexto del modelo |
| `EXTRACTOR_OLLAMA_THINK` | `extractor.ollama.think` | sin definir (compose: `false`) | Razonamiento del modelo |
| `EXTRACTOR_CONCURRENCIA_MAXLLM` | `extractor.concurrencia.max-llm` | `1` | Llamadas simultáneas al LLM |
| `EXTRACTOR_CONCURRENCIA_ESPERAMAXIMA` | `extractor.concurrencia.espera-maxima` | `60s` | Espera máxima en cola (luego 503) |
| `EXTRACTOR_DOCUMENTO_MAXPAGINAS` | `extractor.documento.max-paginas` | `10` | Páginas máximas |
| `EXTRACTOR_DOCUMENTO_DPIIMAGEN` | `extractor.documento.dpi-imagen` | `150` | DPI de rasterización |
| `EXTRACTOR_DOCUMENTO_DPIQR` | `extractor.documento.dpi-qr` | `200` | DPI para buscar el QR |
| `EXTRACTOR_DOCUMENTO_MAXLADOPX` | `extractor.documento.max-lado-px` | `2000` | Lado máximo de imagen enviada al LLM |
| `EXTRACTOR_DOCUMENTO_UMBRALCARACTERESPORPAGINA` | `extractor.documento.umbral-caracteres-por-pagina` | `50` | Mínimo de caracteres para considerar texto utilizable |
| `EXTRACTOR_DEBUG_LOGPROMPTS` | `extractor.debug.log-prompts` | `false` | Ver nota de confidencialidad |

El tamaño máximo de archivo es 10 MB (`spring.servlet.multipart.max-file-size`, variable `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE`).

## Confidencialidad

- Ollama corre en una red Docker interna sin salida a internet; la API no llama a servicios externos.
- Nada del documento, del prompt ni de la respuesta del LLM se escribe en logs ni en disco (PDFBox trabaja en memoria).
- `extractor.debug.log-prompts` (`EXTRACTOR_DEBUG_LOGPROMPTS`) es `false` por defecto. Activarlo registra prompts y respuestas, que contienen datos de las facturas: usarlo solo en diagnóstico, en un entorno controlado, y desactivarlo después.
