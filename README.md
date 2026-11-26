# InvoiceDataExtractor

Servicio REST (Spring Boot 3.5 / Java 21) que extrae campos de facturas argentinas (PDF, JPG, PNG) con un LLM. Camino híbrido: lectura del QR de ARCA, texto del PDF cuando existe e imagen cuando no. Los datos van solo al proveedor de IA que la organización configure (Ollama local, Azure OpenAI o Vertex AI Gemini); con Ollama no salen de la infraestructura.

## Requisitos

- Docker con Compose v2.20 o posterior (`docker compose`).
- Para GPU: NVIDIA Container Toolkit. Sin GPU funciona en CPU, más lento.
- Sin GPU (solo CPU) el modelo es muy lento. En una notebook, un PDF de texto simple de 1 página tardó ~7 minutos (completó), pero la factura `retail-a` (8 líneas de detalle) superó 600 s y la API respondió 504. Con el timeout por defecto (`90s`) casi todo responde 504: subí `EXTRACTOR_OLLAMA_TIMEOUT` (p. ej. `900s`, en un archivo `.env` junto al compose, ver `.env.example`, o como variable de entorno) y usá en `curl` un `--max-time` mayor que ese timeout. Si además subís la concurrencia, subí también `EXTRACTOR_CONCURRENCIA_ESPERAMAXIMA` (la espera en cola cuenta aparte y vence con 503).
- Ajustes de CPU: bajar `EXTRACTOR_DOCUMENTO_MAXLADOPX` (p. ej. `1200`) y el DPI (`EXTRACTOR_DOCUMENTO_DPIIMAGEN`) reduce los tokens de imagen; bajar `EXTRACTOR_OLLAMA_NUMCTX` acelera pero un contexto menor al prompt lo trunca (el log avisa con "posible truncamiento de contexto" cuando `prompt_eval_count` alcanza `num_ctx`).
- Espacio en disco: el modelo por defecto (`qwen3-vl:4b-instruct`, licencia Apache 2.0) ocupa ~3.3 GB.
- Usar la variante **instruct**: el tag `qwen3-vl:4b` es la variante *thinking*, que razona siempre antes de responder (ignora `think: false`); ese razonamiento consume el tope `EXTRACTOR_OLLAMA_NUMPREDICT` y la API responde 422. Con un modelo que razone, `num_predict` cuenta también los tokens de razonamiento.

## Elegir proveedor de IA

`EXTRACTOR_LLM_PROVEEDOR` elige el proveedor: `OLLAMA` (default), `AZURE_OPENAI` o `VERTEX_GEMINI`. Se configura en `.env` (ver `.env.example`). Si falta una variable obligatoria el servicio no arranca y el log nombra las que faltan. Los tres usan temperatura 0 y no hay reintentos ante 429 ni fallback entre proveedores.

**Ollama** (local): `COMPOSE_PROFILES=ollama` levanta el contenedor `ollama`; sin ese perfil no se levanta. Es el único caso que lo necesita.

```env
COMPOSE_PROFILES=ollama
```

**Azure OpenAI**: variables mínimas, sin `COMPOSE_PROFILES`.

```env
EXTRACTOR_LLM_PROVEEDOR=AZURE_OPENAI
EXTRACTOR_AZURE_ENDPOINT=https://mi-recurso.openai.azure.com
EXTRACTOR_AZURE_DEPLOYMENT=mi-deployment
EXTRACTOR_AZURE_APIKEY=...
```

La key se obtiene en el portal de Azure: recurso de Azure OpenAI, "Claves y punto de conexión" (Keys and Endpoint), `KEY 1` o `KEY 2`; el deployment es el nombre que se le dio al modelo desplegado. Opcionales: `EXTRACTOR_AZURE_APIVERSION` (`2024-10-21`), `EXTRACTOR_AZURE_TIMEOUT` (`90s`), `EXTRACTOR_AZURE_MAXTOKENS` (`4096`).

Modelos de razonamiento de Azure: `EXTRACTOR_AZURE_RAZONAMIENTO=true` no envía `temperature` y envía `reasoning_effort` `low`. Hace falta un `EXTRACTOR_AZURE_APIVERSION` que lo acepte (`2024-12-01-preview` o posterior; el servicio avisa al arrancar si queda `2024-10-21`) y conviene subir `EXTRACTOR_AZURE_MAXTOKENS`, porque los tokens de razonamiento cuentan contra el tope.

**Vertex AI / Gemini**: variables mínimas, sin `COMPOSE_PROFILES`.

```env
EXTRACTOR_LLM_PROVEEDOR=VERTEX_GEMINI
EXTRACTOR_VERTEX_PROYECTO=mi-proyecto
EXTRACTOR_VERTEX_REGION=us-central1
EXTRACTOR_VERTEX_MODELO=gemini-2.5-flash
EXTRACTOR_VERTEX_CREDENCIALES=/run/secretos/vertex.json
```

Cuenta de servicio: en Google Cloud, IAM y administración, Cuentas de servicio, crear una y darle el rol `Vertex AI User` en el proyecto; crear una clave JSON y guardarla en `secretos/vertex.json` (la carpeta `secretos/` se monta en solo lectura en `/run/secretos` y no se versiona). Opcionales: `EXTRACTOR_VERTEX_TIMEOUT` (`90s`), `EXTRACTOR_VERTEX_MAXTOKENS` (`4096`).

- Razonamiento de Gemini: `EXTRACTOR_VERTEX_PRESUPUESTORAZONAMIENTO` fija el `thinkingBudget` (p. ej. `0` en 2.5 Flash; algunos modelos exigen un mínimo). Sin definir no se envía.
- Advertencia: con `EXTRACTOR_VERTEX_REGION=global` el servicio registra un aviso y no se garantiza dónde procesa Google los datos. Para residencia de datos usar una región concreta.

**Proxy y CA**: `EXTRACTOR_PROXY_HOST`, `EXTRACTOR_PROXY_PUERTO` y, si el proxy lo pide, `EXTRACTOR_PROXY_USUARIO` y `EXTRACTOR_PROXY_CLAVE`. Aplica solo a Azure y Vertex (incluido el pedido del token de Google); sin `EXTRACTOR_PROXY_HOST` no se usa proxy. El servicio habilita al arrancar la autenticación Basic para túneles HTTPS (un `-Djdk.http.auth.tunneling.disabledSchemes` explícito tiene prioridad). Si el proxy inspecciona TLS, guardar la CA corporativa en PEM en `secretos/ca-proxy.pem` y definir `EXTRACTOR_PROXY_CAEXTRA=/run/secretos/ca-proxy.pem`; se suma a las CA del JDK. El pedido del token de Google usa los timeouts por defecto de la librería (~20 s de conexión y ~20 s de lectura) y no reintenta.

`EXTRACTOR_CONCURRENCIA_MAXLLM` sin definir es `1` con Ollama y `4` con proveedores en la nube.

## Puesta en marcha

Con un proveedor en la nube, saltear el paso 1 y definir antes las variables del proveedor (ver arriba).

1. Descargar el modelo (único paso con acceso a internet):

   ```bash
   ./scripts/descargar-modelo.sh qwen3-vl:4b-instruct
   ```

2. Levantar los servicios (con Ollama, `COMPOSE_PROFILES=ollama` en `.env` o en el entorno):

   ```bash
   docker compose up -d --build
   ```

   Variante con GPU NVIDIA:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build
   ```

Con el perfil `ollama`, el contenedor `ollama` está solo en la red interna `ia` (sin salida a internet ni puertos publicados). Solo `extractor-api` expone el puerto 8080 (`EXTRACTOR_PUERTO`, default `8080` en todas las interfaces; para solo loopback: `EXTRACTOR_PUERTO=127.0.0.1:8080`). La imagen de Ollama está fijada a la versión `0.40.16`.

## Uso

```bash
curl -sS --max-time 600 \
  -F "documento=@factura.pdf" \
  -F "solicitud=@ejemplos/solicitud.json;type=application/json" \
  http://localhost:8080/api/v1/extracciones/sync
```

`ejemplos/solicitud.json` define los campos de cabecera y de detalle (nombre, etiquetas, descripción, tipo `TEXTO | NUMERO | IMPORTE | FECHA | CUIT`). Cada campo vuelve con `valor`, `descripcion` y `fuente` (`QR_ARCA | DOCUMENTO | null`). Dato ausente: `valor: null` y `"Dato no encontrado."`.

Errores en formato RFC 7807:

| Status | Causa |
|---|---|
| 400 | Solicitud inválida |
| 413 | Tamaño o cantidad de páginas |
| 415 | Formato no soportado |
| 422 | JSON del LLM inválido; filtro de contenido del proveedor (tras un reintento); límite de tokens alcanzado (el detalle nombra `EXTRACTOR_OLLAMA_NUMPREDICT`, `EXTRACTOR_AZURE_MAXTOKENS` o `EXTRACTOR_VERTEX_MAXTOKENS`) |
| 503 | Servicio ocupado o proveedor caído; cuota agotada (429 del proveedor: "Se alcanzó el límite de uso del proveedor de IA.", con `Retry-After` si el proveedor lo envía, sin reintento); credenciales (401/403) o proxy rechazados (el log distingue el caso) |
| 504 | Timeout del LLM |

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
| `EXTRACTOR_CONCURRENCIA_MAXLLM` | `extractor.concurrencia.max-llm` | sin definir: `1` con Ollama, `4` con nube | Llamadas simultáneas al LLM |
| `EXTRACTOR_CONCURRENCIA_ESPERAMAXIMA` | `extractor.concurrencia.espera-maxima` | `60s` | Espera máxima en cola (luego 503) |
| `EXTRACTOR_LLM_PROVEEDOR` | `extractor.llm.proveedor` | `OLLAMA` | `OLLAMA`, `AZURE_OPENAI` o `VERTEX_GEMINI` |
| `EXTRACTOR_AZURE_ENDPOINT`, `_DEPLOYMENT`, `_APIKEY` | `extractor.azure.*` | obligatorias con Azure | Recurso, deployment y key |
| `EXTRACTOR_AZURE_APIVERSION` | `extractor.azure.api-version` | `2024-10-21` | Versión de la API |
| `EXTRACTOR_AZURE_TIMEOUT` | `extractor.azure.timeout` | `90s` | Timeout de la llamada |
| `EXTRACTOR_AZURE_MAXTOKENS` | `extractor.azure.max-tokens` | `4096` | Tope de tokens de salida; si se alcanza, 422 |
| `EXTRACTOR_AZURE_RAZONAMIENTO` | `extractor.azure.razonamiento` | `false` | Modelos de razonamiento |
| `EXTRACTOR_VERTEX_PROYECTO`, `_REGION`, `_MODELO`, `_CREDENCIALES` | `extractor.vertex.*` | obligatorias con Vertex | Proyecto, región (o `global`), modelo y JSON de la cuenta de servicio |
| `EXTRACTOR_VERTEX_TIMEOUT` | `extractor.vertex.timeout` | `90s` | Timeout de la llamada |
| `EXTRACTOR_VERTEX_MAXTOKENS` | `extractor.vertex.max-tokens` | `4096` | Tope de tokens de salida; si se alcanza, 422 |
| `EXTRACTOR_VERTEX_PRESUPUESTORAZONAMIENTO` | `extractor.vertex.presupuesto-razonamiento` | sin definir (no se envía) | `thinkingBudget` de Gemini |
| `EXTRACTOR_PROXY_HOST`, `_PUERTO`, `_USUARIO`, `_CLAVE` | `extractor.proxy.*` | sin definir (sin proxy) | Proxy saliente (Azure/Vertex) |
| `EXTRACTOR_PROXY_CAEXTRA` | `extractor.proxy.ca-extra` | sin definir | PEM de la CA corporativa |
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

- Con Ollama: corre en una red Docker interna sin salida a internet y la API no llama a servicios externos. Con Azure OpenAI o Vertex AI el contenido de los documentos se envía al proveedor configurado (y por el proxy, si lo hay); la API key, el token de Google y la clave del proxy nunca se registran en logs.
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
