# InvoiceDataExtractor

Servicio REST (Spring Boot 3.5 / Java 21) que extrae campos de facturas argentinas (PDF, JPG, PNG) con un LLM local servido por Ollama. Camino híbrido: lectura del QR de ARCA, texto del PDF cuando existe e imagen cuando no. Ningún dato sale de tu infraestructura.

## Requisitos

- Docker con Compose v2 (`docker compose`).
- Para GPU: NVIDIA Container Toolkit. Sin GPU funciona en CPU, más lento.
- Sin GPU (solo CPU) el modelo es muy lento. En una notebook, un PDF de texto simple de 1 página tardó ~7 minutos (completó), pero la factura `retail-a` (8 líneas de detalle) superó 600 s y la API respondió 504. Con el timeout por defecto (`90s`) casi todo responde 504: subí `EXTRACTOR_OLLAMA_TIMEOUT` (p. ej. `900s`, en un archivo `.env` junto al compose, ver `.env.example`, o como variable de entorno) y usá en `curl` un `--max-time` mayor que ese timeout. Si además subís la concurrencia, subí también `EXTRACTOR_CONCURRENCIA_ESPERAMAXIMA` (la espera en cola cuenta aparte y vence con 503).
- Ajustes de CPU: bajar `EXTRACTOR_DOCUMENTO_MAXLADOPX` (p. ej. `1200`) y el DPI (`EXTRACTOR_DOCUMENTO_DPIIMAGEN`) reduce los tokens de imagen; bajar `EXTRACTOR_OLLAMA_NUMCTX` acelera pero un contexto menor al prompt lo trunca (el log avisa con "posible truncamiento de contexto" cuando `prompt_eval_count` alcanza `num_ctx`).
- Espacio en disco: el modelo por defecto (`qwen3-vl:4b-instruct`, licencia Apache 2.0) ocupa ~3.3 GB.
- Usar la variante **instruct**: el tag `qwen3-vl:4b` es la variante *thinking*, que razona siempre antes de responder (ignora `think: false`); ese razonamiento consume el tope `EXTRACTOR_OLLAMA_NUMPREDICT` y la API responde 422. Con un modelo que razone, `num_predict` cuenta también los tokens de razonamiento.

## Puesta en marcha

1. Descargar el modelo (único paso con acceso a internet):

   ```bash
   ./scripts/descargar-modelo.sh qwen3-vl:4b-instruct
   ```

2. Levantar los servicios:

   ```bash
   docker compose up -d --build
   ```

   Variante con GPU NVIDIA:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build
   ```

El contenedor `ollama` está solo en la red interna `ia` (sin salida a internet ni puertos publicados). Solo `extractor-api` expone el puerto 8080 (`EXTRACTOR_PUERTO`, default `8080` en todas las interfaces; para solo loopback: `EXTRACTOR_PUERTO=127.0.0.1:8080`). La imagen de Ollama está fijada a la versión `0.40.16`.

## Uso

```bash
curl -sS --max-time 600 \
  -F "documento=@factura.pdf" \
  -F "solicitud=@ejemplos/solicitud.json;type=application/json" \
  http://localhost:8080/api/v1/extracciones/sync
```

`ejemplos/solicitud.json` define los campos de cabecera y de detalle (nombre, etiquetas, descripción, tipo `TEXTO | NUMERO | IMPORTE | FECHA | CUIT`). Cada campo vuelve con `valor`, `descripcion` y `fuente` (`QR_ARCA | DOCUMENTO | null`). Dato ausente: `valor: null` y `"Dato no encontrado."`.

Errores en formato RFC 7807: 400 inválido, 413 tamaño/páginas, 415 formato, 422 JSON del LLM inválido o límite de tokens alcanzado, 503 ocupado u Ollama caído, 504 timeout del LLM.

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
| `EXTRACTOR_OLLAMA_MODELO` | `extractor.ollama.modelo` | `qwen3-vl:4b-instruct` | Modelo |
| `EXTRACTOR_OLLAMA_TIMEOUT` | `extractor.ollama.timeout` | `90s` | Timeout de la llamada al LLM |
| `EXTRACTOR_OLLAMA_NUMCTX` | `extractor.ollama.num-ctx` | `8192` | Contexto del modelo |
| `EXTRACTOR_OLLAMA_NUMPREDICT` | `extractor.ollama.num-predict` | `2048` | Tope de tokens de salida del modelo; si se alcanza, 422 |
| `EXTRACTOR_OLLAMA_THINK` | `extractor.ollama.think` | sin definir (compose: `false`) | Razonamiento del modelo |
| `EXTRACTOR_CONCURRENCIA_MAXLLM` | `extractor.concurrencia.max-llm` | `1` | Llamadas simultáneas al LLM |
| `EXTRACTOR_CONCURRENCIA_ESPERAMAXIMA` | `extractor.concurrencia.espera-maxima` | `60s` | Espera máxima en cola (luego 503) |
| `EXTRACTOR_DOCUMENTO_MAXPAGINAS` | `extractor.documento.max-paginas` | `10` | Páginas máximas |
| `EXTRACTOR_DOCUMENTO_DPIIMAGEN` | `extractor.documento.dpi-imagen` | `150` | DPI de rasterización |
| `EXTRACTOR_DOCUMENTO_DPIQR` | `extractor.documento.dpi-qr` | `200` | DPI para buscar el QR |
| `EXTRACTOR_DOCUMENTO_MAXLADOPX` | `extractor.documento.max-lado-px` | `2000` | Lado máximo de imagen enviada al LLM |
| `EXTRACTOR_DOCUMENTO_UMBRALCARACTERESPORPAGINA` | `extractor.documento.umbral-caracteres-por-pagina` | `50` | Mínimo de caracteres para considerar texto utilizable |
| `EXTRACTOR_DEBUG_LOGPROMPTS` | `extractor.debug.log-prompts` | `false` | Ver nota de confidencialidad |
| `OLLAMA_NUM_PARALLEL` | (contenedor `ollama`) | `1` | Solicitudes en paralelo dentro de Ollama |
| `EXTRACTOR_PUERTO` | (compose) | `8080` | Puerto publicado de la API |

Copiá `.env.example` a `.env` para cambiar cualquiera de ellas con `docker compose`.

El tamaño máximo de archivo es 10 MB (`SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE`; también `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE`, default 11 MB). `SPRING_SERVLET_MULTIPART_FILE_SIZE_THRESHOLD` (default 11 MB) debe ser mayor o igual al máximo de la solicitud: si es menor, Tomcat escribe las partes a disco (`upload_*.tmp`).

## Confidencialidad

- Ollama corre en una red Docker interna sin salida a internet; la API no llama a servicios externos.
- Nada del documento, del prompt ni de la respuesta del LLM se escribe en logs ni en disco (PDFBox trabaja en memoria).
- `extractor.debug.log-prompts` (`EXTRACTOR_DEBUG_LOGPROMPTS`) es `false` por defecto. Activarlo registra prompts y respuestas, que contienen datos de las facturas: usarlo solo en diagnóstico, en un entorno controlado, y desactivarlo después.

## Cómo medir

El benchmark (`BenchmarkRunner`) envía cada documento de `benchmark/dataset/` al servicio en marcha, compara contra `esperado.json` y escribe un CSV en `benchmark/resultados/` (no versionado; columnas `documento,forma,modo,qrPresente,status,totalMs,deteccionMs,qrMs,extraccionTextoMs,rasterizacionMs,llmMs,esperaColaMs,aciertos,camposTotales,inventados,omitidos,cumple60s`) con un resumen por modo (p50/p95 de `totalMs`, % de aciertos, inventados, % de documentos <= 60 s).

Dataset completo (12 documentos):

```bash
./scripts/mvn.sh -q test-compile exec:java -Dexec.classpathScope=test \
  -Dexec.mainClass=ar.comprobantes.extractor.benchmark.BenchmarkRunner \
  -Dexec.args="--url http://host.docker.internal:8080"
```

En Linux, `host.docker.internal` no resuelve dentro del contenedor de Maven: usar red del host con `MVN_DOCKER_ARGS="--network host"` y `--url http://localhost:8080`.

Solo algunos documentos (carpetas cuyo nombre contiene el texto):

```bash
MVN_DOCKER_ARGS="--network host" ./scripts/mvn.sh -q test-compile exec:java -Dexec.classpathScope=test \
  -Dexec.mainClass=ar.comprobantes.extractor.benchmark.BenchmarkRunner \
  -Dexec.args="--url http://localhost:8080 --filtro retail-a"
```

Concurrencia: agregar `--concurrencia N` a `-Dexec.args` y levantar el servicio con `EXTRACTOR_CONCURRENCIA_MAXLLM=N` **y** `OLLAMA_NUM_PARALLEL=N` (si no, las solicitudes esperan en cola o Ollama las serializa). Otras opciones: `--dataset`, `--salida`.

Comparar modelos: reiniciar el servicio con otro `EXTRACTOR_OLLAMA_MODELO` (el modelo debe estar descargado en Ollama), volver a correr el benchmark y comparar los CSV. Con CPU y modelos grandes subir `EXTRACTOR_OLLAMA_TIMEOUT` (el benchmark espera hasta 15 min por documento).

### Resultados iniciales

Entorno: notebook solo CPU (Intel Comet Lake-U, 8 hilos, sin GPU), modelo `qwen3-vl:4b`, `EXTRACTOR_OLLAMA_TIMEOUT=600s`, concurrencia 1. **Dataset parcial: solo `retail-a` (3 documentos).**

| Documento | Modo | Status | totalMs | Aciertos | Inventados |
|---|---|---|---|---|---|
| retail-a-texto | - | 504 | 600576 | n/d | n/d |
| retail-a-escaneo | - | 504 | 600739 | n/d | n/d |
| retail-a-foto | - | 504 | 600934 | n/d | n/d |

Los tres documentos agotaron el timeout del LLM (600 s) y devolvieron 504, por lo que no hay métricas de precisión. Conclusión provisional: en esta notebook sin GPU, `qwen3-vl:4b` no cumple el objetivo de 60 s ni termina en 10 min con estos comprobantes (varias líneas de detalle). Falta medir el resto del dataset y concurrencia 2. **Nota (2026-11-17):** esa medición usó `qwen3-vl:4b`, que resultó ser la variante *thinking*; el tiempo se iba en razonamiento interno. Hay que repetirla con `qwen3-vl:4b-instruct`.
