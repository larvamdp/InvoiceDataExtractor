# Proveedor de IA configurable — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que cada instalación elija por configuración su proveedor de IA (`OLLAMA`, `AZURE_OPENAI`, `VERTEX_GEMINI`), con salida a la nube a través de proxy corporativo, sin cambiar el contrato de la API.

**Architecture:** Dos implementaciones nuevas de `LlmCliente` (`AzureOpenAiLlmCliente`, `VertexGeminiLlmCliente`) escritas con `RestClient` como `OllamaLlmCliente`; una pieza común (`HttpNube`) arma el `HttpClient` con proxy y CA extra y traduce los errores HTTP a las excepciones del proyecto; cada cliente adapta el esquema canónico de `EsquemaRespuesta` a su proveedor. Una `@Configuration` elige el bean según `extractor.llm.proveedor` y valida la configuración al arrancar.

**Tech Stack:** Spring Boot 3.5, Java 21, `RestClient` sobre `java.net.http.HttpClient`, `com.google.auth:google-auth-library-oauth2-http`, WireMock 3.13, JUnit 5 / AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-25-proveedores-llm-design.md`

## Global Constraints

- Contrato de la API sin cambios: endpoint, `ResultadoExtraccion`, `Metadatos` (incluido `modelo`), códigos RFC 7807.
- Las variables `EXTRACTOR_OLLAMA_*` siguen funcionando igual; sin `EXTRACTOR_LLM_PROVEEDOR` el servicio se comporta como hoy.
- Logs y mensajes de error: nunca contenido del documento, prompt, respuesta del modelo, API key, token, clave del proxy ni `getMessage()` de excepciones de terceros.
- Temperatura 0 en los tres proveedores (salvo Azure con `razonamiento=true`, que no envía `temperature`).
- Defaults: `extractor.azure.timeout=90s`, `extractor.azure.max-tokens=4096`, `extractor.azure.api-version=2024-10-21`, `extractor.vertex.timeout=90s`, `extractor.vertex.max-tokens=4096`; concurrencia por defecto `1` con Ollama y `4` con nube.
- Modelos/deployments de nube sin default: obligatorios.
- Sin reintentos ante 429; sin fallback entre proveedores.
- Ejecutar Maven con `./scripts/mvn.sh` (no hay JDK local). Las primeras corridas descargan dependencias.
- Desvío menor respecto del spec (decisión del plan): la CA extra es `extractor.proxy.ca-extra` / `EXTRACTOR_PROXY_CAEXTRA` (no `EXTRACTOR_CA_EXTRA`), porque el binding relajado de Spring no puede mapear `EXTRACTOR_CA_EXTRA` a un campo del bloque `proxy`.

## Review Focus

1. **Proxy con usuario sobre HTTPS real.** El JDK deshabilita Basic en túneles CONNECT; si no se habilita, en producción falla aunque los tests (HTTP plano) pasen. Test en Task 3: `HttpNube` deja `jdk.http.auth.tunneling.disabledSchemes` vacío cuando hay usuario.
2. **Gemini bloquea el prompt o no devuelve candidatos** (`promptFeedback.blockReason`, `candidates` vacío, solo partes `thought`): debe ser 422 con reintento, nunca 500. Tests en Task 6.
3. **Azure responde 200 con `content` null** (refusal o `finish_reason: content_filter`) o sin `choices`: 422, nunca NPE. Tests en Task 4.
4. **Token de Google que falla en tiempo de ejecución** (cuenta de servicio borrada, proxy caído al pedir token): 503, nunca 500. Test en Task 5.
5. **`Retry-After` como fecha HTTP o basura**: fecha → segundos; ilegible → sin header, mismo 503. Tests en Task 3.

---

### Task 1: Configuración de proveedores y concurrencia por defecto

**Files:**
- Create: `src/main/java/ar/comprobantes/extractor/config/ProveedorLlm.java`
- Modify: `src/main/java/ar/comprobantes/extractor/config/ExtractorProperties.java`
- Modify: `src/main/java/ar/comprobantes/extractor/servicio/LimitadorConcurrencia.java`
- Test: `src/test/java/ar/comprobantes/extractor/config/ExtractorPropertiesTest.java`, `src/test/java/ar/comprobantes/extractor/servicio/LimitadorConcurrenciaTest.java`

**Interfaces:**
- Produces:
  - `enum ProveedorLlm { OLLAMA, AZURE_OPENAI, VERTEX_GEMINI }`
  - `record ExtractorProperties(Llm llm, Ollama ollama, Azure azure, Vertex vertex, Proxy proxy, Concurrencia concurrencia, Documento documento, Debug debug)` — constructor canónico anotado `@ConstructorBinding`, todos los bloques `@DefaultValue`.
  - Constructor de compatibilidad `ExtractorProperties(Ollama, Concurrencia, Documento, Debug)` que completa el resto con `null` (los tests existentes lo usan sin cambios).
  - `record Llm(@DefaultValue("OLLAMA") ProveedorLlm proveedor)`
  - `record Azure(String endpoint, String deployment, String apiKey, @DefaultValue("2024-10-21") String apiVersion, @DefaultValue("90s") Duration timeout, @DefaultValue("4096") int maxTokens, @DefaultValue("false") boolean razonamiento)`
  - `record Vertex(String proyecto, String region, String modelo, Path credenciales, @DefaultValue("90s") Duration timeout, @DefaultValue("4096") int maxTokens, Integer presupuestoRazonamiento)`
  - `record Proxy(String host, Integer puerto, String usuario, String clave, Path caExtra)` con `boolean activo()` = host no vacío.
  - `Concurrencia(Integer maxLlm, @DefaultValue("60s") Duration esperaMaxima)` — `maxLlm` deja de tener default.
  - `ProveedorLlm ExtractorProperties.proveedor()` → `llm == null ? OLLAMA : llm.proveedor()`.
  - `int ExtractorProperties.maxLlmEfectivo()` → `maxLlm` si no es null; si no, `1` para OLLAMA y `4` para nube.
  - `toString()` de `Azure` y `Proxy` enmascara `apiKey`/`clave` (`"****"`), para que un log accidental del objeto no las filtre.

- [ ] **Step 1: Tests que fallan.** En `ExtractorPropertiesTest`: en `defaults()`, `p.proveedor()==OLLAMA`, `p.concurrencia().maxLlm()` null, `p.maxLlmEfectivo()==1`, `p.azure().apiVersion()=="2024-10-21"`, `p.azure().maxTokens()==4096`, `p.azure().razonamiento()` false, `p.vertex().maxTokens()==4096`, `p.vertex().timeout()==90s`, `p.proxy().activo()` false. Nuevos: `nubeTieneConcurrencia4` (`extractor.llm.proveedor=VERTEX_GEMINI` → `maxLlmEfectivo()==4`), `concurrenciaExplicitaManda` (`AZURE_OPENAI` + `extractor.concurrencia.max-llm=2` → `2`), `variablesDeEntorno` (con `SystemEnvironmentPropertySource` como el test existente: `EXTRACTOR_LLM_PROVEEDOR=AZURE_OPENAI`, `EXTRACTOR_AZURE_APIKEY=k`, `EXTRACTOR_VERTEX_PRESUPUESTORAZONAMIENTO=0`, `EXTRACTOR_PROXY_CAEXTRA=/x.pem` → se bindean), `toStringNoMuestraSecretos` (`new Azure(..., "secreta", ...).toString()` no contiene `secreta`; idem `Proxy` con clave). En `LimitadorConcurrenciaTest`, el helper existente sigue compilando (usa el constructor de 4 args).
- [ ] **Step 2:** `./scripts/mvn.sh -q test -Dtest='ExtractorPropertiesTest,LimitadorConcurrenciaTest'` → FAIL de compilación.
- [ ] **Step 3: Implementar** los records y métodos de arriba; `LimitadorConcurrencia` usa `propiedades.maxLlmEfectivo()`.
- [ ] **Step 4:** `./scripts/mvn.sh -q test` → todo verde (los demás tests usan el constructor de compatibilidad).
- [ ] **Step 5: Commit** `feat: configuración de proveedor de IA, proxy y concurrencia por defecto según proveedor`.

### Task 2: Cuota agotada (429) con `Retry-After`

**Files:**
- Create: `src/main/java/ar/comprobantes/extractor/error/LlmCuotaAgotadaException.java`
- Modify: `src/main/java/ar/comprobantes/extractor/api/ManejadorErrores.java`
- Test: `src/test/java/ar/comprobantes/extractor/api/ManejadorErroresTest.java`

**Interfaces:**
- Produces: `LlmCuotaAgotadaException(String mensaje, Long retryAfterSegundos)` — `status()` 503, `titulo()` "Modelo de lenguaje no disponible", `Long retryAfterSegundos()` (null = sin header). Mensaje fijo usado por los clientes: `"Se alcanzó el límite de uso del proveedor de IA."`.

- [ ] **Step 1: Tests que fallan** en `ManejadorErroresTest` (mismo estilo que los existentes): `cuotaAgotadaEs503ConRetryAfter` → status 503, header `Retry-After: 30`, `detail` igual al mensaje; `cuotaAgotadaSinRetryAfterNoMandaHeader` → sin header.
- [ ] **Step 2:** `./scripts/mvn.sh -q test -Dtest=ManejadorErroresTest` → FAIL.
- [ ] **Step 3: Implementar.** En el handler de `ExtraccionException`, si es `LlmCuotaAgotadaException` con segundos no nulos, agregar `HttpHeaders.RETRY_AFTER`. `ExtraccionService` no reintenta: no extiende `RespuestaLlmInvalidaException`.
- [ ] **Step 4:** mismo comando → PASS.
- [ ] **Step 5: Commit** `feat: 503 con Retry-After cuando el proveedor de IA agota la cuota`.

### Task 3: `HttpNube` — cliente HTTP con proxy, CA extra y mapeo de errores

**Files:**
- Create: `src/main/java/ar/comprobantes/extractor/llm/nube/HttpNube.java`
- Create: `src/main/java/ar/comprobantes/extractor/llm/nube/ErroresNube.java`
- Create: `src/main/java/ar/comprobantes/extractor/llm/nube/ConfianzaTls.java`
- Test: `src/test/java/ar/comprobantes/extractor/llm/nube/HttpNubeTest.java`, `ErroresNubeTest.java`, `ConfianzaTlsTest.java`

**Interfaces:**
- Consumes: `ExtractorProperties.Proxy` (Task 1), `LlmCuotaAgotadaException` (Task 2).
- Produces:
  - `ConfianzaTls.contexto(Path pemExtra) -> SSLContext` (CA del JDK + todas las del PEM; `null` → `SSLContext.getDefault()`), `ConfianzaTls.keyStore(Path pemExtra) -> KeyStore` (mismas CA, para el transporte de Google). PEM ilegible o sin certificados → `IllegalStateException("EXTRACTOR_PROXY_CAEXTRA: no se pudo leer el certificado")`.
  - `HttpNube.restClient(String baseUrl, Duration timeout, ExtractorProperties.Proxy proxy) -> RestClient`: HTTP/1.1, connect timeout 10 s, read timeout `timeout`, `ProxySelector.of(host:puerto)` si `proxy.activo()`, `Authenticator` que responde solo a `RequestorType.PROXY` si hay usuario, `SSLContext` de `ConfianzaTls`. Si hay usuario, fija `System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")` y `jdk.http.auth.proxying.disabledSchemes` a `""` antes de construir el cliente.
  - `HttpNube.proxyJava(ExtractorProperties.Proxy) -> java.net.Proxy` (`Proxy.NO_PROXY` si inactivo) — lo usa Task 5.
  - `ErroresNube.traducir(Exception e, String proveedor, String modelo, boolean hayProxy) -> ExtraccionException` (siempre devuelve, el cliente hace `throw`):
    - `RestClientResponseException` 429 → `LlmCuotaAgotadaException` con `Retry-After` parseado (`segundos` enteros o fecha RFC 1123 → segundos desde ahora, mínimo 0; ilegible → null).
    - 401/403 → `LlmNoDisponibleException`, log `"credenciales rechazadas por el proveedor: proveedor={} status={}"`.
    - 407 → `LlmNoDisponibleException`, log `"el proxy rechazó la autenticación"`.
    - Otros status → `LlmNoDisponibleException`, log con status; para 400 también el `error.code` del cuerpo si es JSON con ese campo string (nunca `error.message`). Si `error.code` es `content_filter` → `RespuestaLlmInvalidaException("El proveedor de IA bloqueó el contenido.")`.
    - `ResourceAccessException` con timeout de lectura (lógica de `OllamaLlmCliente.esTimeout`, movida aquí como `static boolean esTimeout(Throwable)` y reutilizada por Ollama) → `LlmTimeoutException("El modelo excedió el tiempo máximo de respuesta.")`.
    - Otra `ResourceAccessException` → `LlmNoDisponibleException`; log `"no se pudo conectar (vía proxy)"` o `"no se pudo conectar con el proveedor"` según `hayProxy`.
    - Otra `RestClientException` → `LlmNoDisponibleException`, log solo con la clase.
    - Mensaje de `LlmNoDisponibleException` siempre `"El servicio de IA no está disponible."`.

- [ ] **Step 1: Tests que fallan.**
  - `ConfianzaTlsTest`: `agregaCaDelPem` (generar una CA autofirmada en el test con `keytool` vía `KeyStore`/`CertificateFactory` o un PEM fijo en `src/test/resources/tls/ca-prueba.pem` → el `KeyStore` contiene ese certificado y además alguna CA del JDK); `pemInvalidoFalla` (archivo con texto → `IllegalStateException` con mensaje que nombra `EXTRACTOR_PROXY_CAEXTRA`).
  - `HttpNubeTest` (WireMock como proxy: un `WireMockServer` stubbeado en path `/v1/x`; el cliente apunta a `http://proveedor.invalid` y usa el WireMock como proxy): `pasaPorElProxy` (la respuesta llega y WireMock registró el request); `autenticaAnteElProxy` (stub 407 con `Proxy-Authenticate: Basic realm="p"` sin `Proxy-Authorization`, 200 con `Proxy-Authorization: Basic dTpj` → OK); `sinProxyVaDirecto`; `habilitaBasicEnTunel` (con usuario, `System.getProperty("jdk.http.auth.tunneling.disabledSchemes")` es `""`).
  - `ErroresNubeTest` (`@ExtendWith(OutputCaptureExtension)`): una prueba por fila de la tabla anterior, incluidas `retryAfterFechaHttp`, `retryAfterIlegibleEsNull`, `error400LogueaCodeNoMessage` (cuerpo `{"error":{"code":"invalid_parameter","message":"SECRETO"}}` → log contiene `invalid_parameter`, no `SECRETO`), `contentFilterEs422`.
- [ ] **Step 2:** `./scripts/mvn.sh -q test -Dtest='ConfianzaTlsTest,HttpNubeTest,ErroresNubeTest'` → FAIL.
- [ ] **Step 3: Implementar** las tres clases; `OllamaLlmCliente` pasa a usar `ErroresNube.esTimeout`.
- [ ] **Step 4:** `./scripts/mvn.sh -q test` → todo verde (incluye `OllamaLlmClienteTest` sin cambios).
- [ ] **Step 5: Commit** `feat: cliente HTTP para proveedores en la nube con proxy, CA extra y mapeo de errores`.

### Task 4: Cliente Azure OpenAI y su adaptador de esquema

**Files:**
- Create: `src/main/java/ar/comprobantes/extractor/llm/nube/EsquemaAzure.java`
- Create: `src/main/java/ar/comprobantes/extractor/llm/nube/AzureOpenAiLlmCliente.java`
- Test: `src/test/java/ar/comprobantes/extractor/llm/nube/EsquemaAzureTest.java`, `AzureOpenAiLlmClienteTest.java`

**Interfaces:**
- Consumes: `HttpNube`, `ErroresNube` (Task 3); `ExtractorProperties.Azure/Proxy/Debug` (Task 1); `EsquemaRespuesta.para(...)` como fuente de esquemas en tests.
- Produces:
  - `EsquemaAzure.adaptar(ObjectNode canonico) -> ObjectNode` (copia; no muta el original).
  - `AzureOpenAiLlmCliente(ExtractorProperties props)` implementa `LlmCliente`; `modelo()` = deployment. Sin `@Component` (el bean lo crea Task 7).

Reglas de `EsquemaAzure` (strict):
- Todo objeto con `properties` lleva `additionalProperties: false` y `required` = todas sus propiedades.
- Propiedades de `clavesQr`: tipo `["array","null"]` (el parser ya trata null como sin claves).
- `detalle` con `maxItems: 0` (sin campos de detalle) se elimina de `properties` y de `required` (el parser ignora `detalle` cuando no hay campos pedidos).
- Orden de propiedades conservado.

Cuerpo de `POST {endpoint}/openai/deployments/{deployment}/chat/completions?api-version={apiVersion}`, header `api-key`:
- `messages`: `system` con texto; `user` con `content` = `[{"type":"text","text":usuario}, {"type":"image_url","image_url":{"url":"data:image/png;base64,<img>"}}...]`.
- `response_format: {"type":"json_schema","json_schema":{"name":"extraccion","strict":true,"schema":<adaptado>}}`.
- `max_completion_tokens: maxTokens`; `temperature: 0` salvo `razonamiento=true`, en cuyo caso no hay `temperature` y sí `reasoning_effort: "low"` (aceptado por todos los modelos de razonamiento de Azure).

Respuesta:
- `choices[0].finish_reason == "length"` → `LlmLimiteTokensException("La respuesta del modelo superó el límite de tokens (EXTRACTOR_AZURE_MAXTOKENS).")`.
- `finish_reason == "content_filter"`, `message.refusal` no nulo, `content` no textual o `choices` vacío → `RespuestaLlmInvalidaException("El proveedor de IA no devolvió una respuesta utilizable.")`.
- Errores HTTP/conexión → `throw ErroresNube.traducir(e, "AZURE_OPENAI", deployment, proxy.activo())`.
- Log info: `"Azure OpenAI completó: deployment={}, imagenes={}, duracionMs={}, prompt_tokens={}, completion_tokens={}"`. `log-prompts` igual que Ollama.

- [ ] **Step 1: Tests que fallan.**
  - `EsquemaAzureTest` (esquemas reales de `new EsquemaRespuesta().para(solicitud, hayQr)`): `todosLosObjetosSonEstrictos` (recorrer y verificar `additionalProperties:false` y `required` == keys); `clavesQrNullable` (`clavesQr.properties.<campo>.type == ["array","null"]` y `clavesQr.required` contiene todos los campos de cabecera); `sinCamposDeDetalleSeQuitaDetalle`; `conservaOrdenCabeceraClavesQrDetalle`; `noMutaElOriginal`.
  - `AzureOpenAiLlmClienteTest` (WireMock como endpoint, `@ExtendWith(OutputCaptureExtension)`): `enviaCuerpoEsperado` (URL con `api-version=2024-10-21`, header `api-key: k`, `$.response_format.json_schema.strict == true`, `$.temperature == 0`, `$.max_completion_tokens == 4096`, `$.messages[1].content[1].image_url.url` empieza con `data:image/png;base64,AAAA`); `razonamientoOmiteTemperatura` (sin `temperature`, `reasoning_effort == "low"`); `devuelveContenido`; `lengthEs422SinReintento` (`LlmLimiteTokensException`); `contentFilterEs422`, `refusalEs422`, `contentNullEs422`, `sinChoicesEs422` (`RespuestaLlmInvalidaException`); `cuota429Es503ConRetryAfter`; `credenciales401Es503`; `timeoutEs504`; `logsSinKeyNiContenido` (respuesta con `"SECRETO"` en content y key `"KEY-SECRETA"` → salida capturada no contiene ninguna de las dos).
- [ ] **Step 2:** `./scripts/mvn.sh -q test -Dtest='EsquemaAzureTest,AzureOpenAiLlmClienteTest'` → FAIL.
- [ ] **Step 3: Implementar** `EsquemaAzure` y `AzureOpenAiLlmCliente` con las reglas de arriba.
- [ ] **Step 4:** mismo comando → PASS.
- [ ] **Step 5: Commit** `feat: cliente Azure OpenAI con salida estructurada estricta`.

### Task 5: Token de Google con cuenta de servicio, vía proxy

**Files:**
- Modify: `pom.xml` (dependencia `com.google.auth:google-auth-library-oauth2-http`, versión en `<properties>` como `google-auth.version`, `1.30.0` o la última 1.x estable al implementar)
- Create: `src/main/java/ar/comprobantes/extractor/llm/nube/ProveedorToken.java`
- Create: `src/main/java/ar/comprobantes/extractor/llm/nube/TokenCuentaServicio.java`
- Test: `src/test/java/ar/comprobantes/extractor/llm/nube/TokenCuentaServicioTest.java`

**Interfaces:**
- Consumes: `HttpNube.proxyJava`, `ConfianzaTls.keyStore` (Task 3).
- Produces:
  - `interface ProveedorToken { String token(); }` — lanza `LlmNoDisponibleException` si no puede obtenerlo.
  - `TokenCuentaServicio(Path credenciales, ExtractorProperties.Proxy proxy)` implementa `ProveedorToken`. El constructor carga el JSON con `GoogleCredentials.fromStream(in, transportFactory).createScoped("https://www.googleapis.com/auth/cloud-platform")`; `transportFactory` devuelve un `NetHttpTransport` con `setProxy(HttpNube.proxyJava(proxy))` y `trustCertificates(ConfianzaTls.keyStore(caExtra))`. Si hay usuario de proxy, instala un `java.net.Authenticator` por defecto que solo responde a `RequestorType.PROXY`. Archivo inexistente, ilegible o que no sea de tipo `service_account` → `IllegalStateException("EXTRACTOR_VERTEX_CREDENCIALES: <motivo sin contenido del archivo>")`.
  - `token()`: `refreshIfExpired()` y `getAccessToken().getTokenValue()`; `IOException` → `LlmNoDisponibleException("El servicio de IA no está disponible.")` con log `"no se pudo obtener el token de Google (vía proxy|directo)"`, sin mensaje de la excepción.

- [ ] **Step 1: Tests que fallan.** El test genera un par RSA y escribe un JSON `service_account` temporal cuyo `token_uri` apunta a `http://oauth.invalid/token`; un WireMock actúa de proxy y stubbea `POST /token` → `{"access_token":"tok-1","expires_in":3600,"token_type":"Bearer"}`. Casos: `obtieneTokenViaProxy` (`token()=="tok-1"` y WireMock recibió el POST); `cacheaElToken` (dos llamadas → un solo POST); `tokenFallaEs503` (stub 500 → `LlmNoDisponibleException`, sin 500 de la API); `archivoInexistenteFallaAlCrear`; `archivoNoServiceAccountFallaAlCrear` (`{"type":"authorized_user",...}`) — ambos `IllegalStateException` con mensaje que nombra `EXTRACTOR_VERTEX_CREDENCIALES`.
- [ ] **Step 2:** `./scripts/mvn.sh -q test -Dtest=TokenCuentaServicioTest` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** mismo comando → PASS; `./scripts/mvn.sh -q test` → todo verde.
- [ ] **Step 5: Commit** `feat: token de Google con cuenta de servicio a través del proxy`.

### Task 6: Cliente Vertex Gemini y su adaptador de esquema

**Files:**
- Create: `src/main/java/ar/comprobantes/extractor/llm/nube/EsquemaGemini.java`
- Create: `src/main/java/ar/comprobantes/extractor/llm/nube/VertexGeminiLlmCliente.java`
- Test: `src/test/java/ar/comprobantes/extractor/llm/nube/EsquemaGeminiTest.java`, `VertexGeminiLlmClienteTest.java`

**Interfaces:**
- Consumes: `HttpNube`, `ErroresNube` (Task 3); `ProveedorToken` (Task 5); `ExtractorProperties.Vertex/Proxy/Debug`.
- Produces:
  - `EsquemaGemini.adaptar(ObjectNode canonico) -> ObjectNode` (copia).
  - `VertexGeminiLlmCliente(ExtractorProperties props, ProveedorToken token)` implementa `LlmCliente`; `modelo()` = `vertex.modelo`. Constructor de paquete con `String baseUrl` para tests.
  - `static String VertexGeminiLlmCliente.urlBase(String region)` → `https://aiplatform.googleapis.com` si `region` es `global` (sin distinguir mayúsculas), si no `https://{region}-aiplatform.googleapis.com`.

Reglas de `EsquemaGemini` (decisión del plan: se usa `responseSchema`, subconjunto OpenAPI, porque lo aceptan todos los Gemini 2.x y permite fijar el orden con `propertyOrdering`):
- `type` en mayúsculas (`OBJECT`, `ARRAY`, `STRING`); `["string","null"]` → `{"type":"STRING","nullable":true}`.
- Todo `OBJECT` con `properties` lleva `propertyOrdering` = keys en orden; la raíz queda `cabecera, clavesQr, detalle`.
- `detalle` con `maxItems: 0` se elimina (como en Azure).
- `enum` y `required` se conservan; no se agrega `additionalProperties`.

Cuerpo de `POST {urlBase}/v1/projects/{proyecto}/locations/{region}/publishers/google/models/{modelo}:generateContent`, header `Authorization: Bearer <token.token()>`:
- `systemInstruction: {"parts":[{"text":sistema}]}`; `contents: [{"role":"user","parts":[{"text":usuario}, {"inlineData":{"mimeType":"image/png","data":<img>}}...]}]`.
- `generationConfig: {"temperature":0, "maxOutputTokens":maxTokens, "responseMimeType":"application/json", "responseSchema":<adaptado>}` y, solo si `presupuestoRazonamiento != null`, `"thinkingConfig":{"thinkingBudget":N}`.

Respuesta:
- `promptFeedback.blockReason` presente, `candidates` vacío, `finishReason` en `SAFETY|RECITATION|PROHIBITED_CONTENT|BLOCKLIST|SPII`, o sin partes de texto no-`thought` → `RespuestaLlmInvalidaException("El proveedor de IA no devolvió una respuesta utilizable.")`.
- `finishReason == "MAX_TOKENS"` → `LlmLimiteTokensException("La respuesta del modelo superó el límite de tokens (EXTRACTOR_VERTEX_MAXTOKENS).")`.
- Texto = concatenación de `candidates[0].content.parts[*].text` cuyas partes no tengan `thought: true`.
- Errores → `ErroresNube.traducir(e, "VERTEX_GEMINI", modelo, proxy.activo())`.
- Log info con `promptTokenCount`, `candidatesTokenCount`, `thoughtsTokenCount` de `usageMetadata`.

- [ ] **Step 1: Tests que fallan.**
  - `EsquemaGeminiTest`: `tiposEnMayusculasYNullable`; `propertyOrderingEnTodosLosObjetos` (raíz con QR == `["cabecera","clavesQr","detalle"]`); `enumDeClavesQrSeConserva`; `sinCamposDeDetalleSeQuitaDetalle`; `noMutaElOriginal`.
  - `VertexGeminiLlmClienteTest` (WireMock como endpoint, `ProveedorToken` falso que devuelve `"tok-1"`, `OutputCaptureExtension`): `urlRegionalYGlobal` (`urlBase("us-central1")`, `urlBase("global")`); `enviaCuerpoEsperado` (path con proyecto/región/modelo, `Authorization: Bearer tok-1`, `$.generationConfig.temperature == 0`, `maxOutputTokens == 4096`, `responseSchema.propertyOrdering[0] == "cabecera"`, `$.contents[0].parts[1].inlineData.mimeType == "image/png"`, sin `thinkingConfig`); `presupuestoRazonamientoSeEnvia` (`presupuestoRazonamiento=0` → `thinkingBudget == 0`); `concatenaPartesIgnorandoThought`; `maxTokensEs422`; `promptBloqueadoEs422`; `safetyEs422`; `soloThoughtEs422`; `sinCandidatosEs422`; `cuota429Es503`; `tokenFallaEs503` (token falso lanza `LlmNoDisponibleException` → se propaga); `timeoutEs504`; `logsSinTokenNiContenido`.
- [ ] **Step 2:** `./scripts/mvn.sh -q test -Dtest='EsquemaGeminiTest,VertexGeminiLlmClienteTest'` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** mismo comando → PASS.
- [ ] **Step 5: Commit** `feat: cliente Vertex Gemini con esquema ordenado y región o global`.

### Task 7: Selección del proveedor y validación al arrancar

**Files:**
- Create: `src/main/java/ar/comprobantes/extractor/config/LlmConfiguracion.java`
- Modify: `src/main/java/ar/comprobantes/extractor/llm/OllamaLlmCliente.java` (quitar `@Component`)
- Test: `src/test/java/ar/comprobantes/extractor/config/LlmConfiguracionTest.java`

**Interfaces:**
- Consumes: los tres clientes (Tasks 4, 6 y el existente), `TokenCuentaServicio` (Task 5), `ExtractorProperties` (Task 1).
- Produces: `@Configuration LlmConfiguracion` con `@Bean LlmCliente llmCliente(ExtractorProperties p)` que valida y construye según `p.proveedor()`.

Validación (antes de construir; falla con `IllegalStateException` cuyo mensaje nombra **todas** las variables faltantes, sin valores):
- `AZURE_OPENAI`: `EXTRACTOR_AZURE_ENDPOINT`, `EXTRACTOR_AZURE_DEPLOYMENT`, `EXTRACTOR_AZURE_APIKEY`.
- `VERTEX_GEMINI`: `EXTRACTOR_VERTEX_PROYECTO`, `EXTRACTOR_VERTEX_REGION`, `EXTRACTOR_VERTEX_MODELO`, `EXTRACTOR_VERTEX_CREDENCIALES` (y archivo legible — lo verifica `TokenCuentaServicio`).
- Proxy con host sin puerto → `EXTRACTOR_PROXY_PUERTO`; usuario sin clave → `EXTRACTOR_PROXY_CLAVE`.
- Log info al construir: `"Proveedor de IA: proveedor={}, modelo={}, destino={}, proxy={}"` (destino = endpoint de Azure, región de Vertex o URL de Ollama; proxy = `host:puerto` o `no`). Con Vertex `global`, además `log.warn("Vertex en ubicación global: Google puede procesar los datos en cualquier región.")`.

- [ ] **Step 1: Tests que fallan** (`ApplicationContextRunner` con `LlmConfiguracion` + `@EnableConfigurationProperties(ExtractorProperties.class)`, `OutputCaptureExtension`): `porDefectoOllama` (bean es `OllamaLlmCliente`); `azureCompleto` (bean `AzureOpenAiLlmCliente`, log contiene endpoint y no la key); `azureIncompletoNoArranca` (solo proveedor → `ctx.getStartupFailure()` con mensaje que contiene las tres variables); `vertexCompleto` (JSON de cuenta de servicio de prueba de Task 5 → bean `VertexGeminiLlmCliente`); `vertexGlobalAdvierte`; `vertexSinCredencialesNoArranca`; `proxySinPuertoNoArranca`. Además `./scripts/mvn.sh -q test` debe seguir verde: `@SpringBootTest` existentes arrancan con Ollama por defecto.
- [ ] **Step 2:** `./scripts/mvn.sh -q test -Dtest=LlmConfiguracionTest` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** `./scripts/mvn.sh -q test` → todo verde.
- [ ] **Step 5: Commit** `feat: elegir proveedor de IA por configuración y validar al arrancar`.

### Task 8: Compose, `.env.example` y README

**Files:**
- Modify: `docker-compose.yml`, `.env.example`, `README.md`, `.gitignore`
- Create: `secretos/.gitkeep`

Decisiones:
- `ollama` con `profiles: [ollama]`; `extractor-api.depends_on.ollama: {condition: service_started, required: false}` (Compose ≥ 2.20).
- `extractor-api` monta `./secretos:/run/secretos:ro` y reenvía, con default vacío, `EXTRACTOR_LLM_PROVEEDOR`, `EXTRACTOR_AZURE_*` (ENDPOINT, DEPLOYMENT, APIKEY, APIVERSION, TIMEOUT, MAXTOKENS, RAZONAMIENTO), `EXTRACTOR_VERTEX_*` (PROYECTO, REGION, MODELO, CREDENCIALES, TIMEOUT, MAXTOKENS, PRESUPUESTORAZONAMIENTO), `EXTRACTOR_PROXY_*` (HOST, PUERTO, USUARIO, CLAVE, CAEXTRA). `EXTRACTOR_CONCURRENCIA_MAXLLM` pasa a reenviarse sin default (`${EXTRACTOR_CONCURRENCIA_MAXLLM:-}`) para que aplique el default por proveedor. Variables vacías no deben pisar defaults de Spring: verificar que un valor vacío para `Duration`/`int` no rompa el binding; si rompe, reenviar esas variables solo con default explícito igual al de Spring.
- `.gitignore`: `secretos/*` y `!secretos/.gitkeep`.
- `.env.example`: `COMPOSE_PROFILES=ollama` (comentado según el caso) y un bloque por proveedor, con `EXTRACTOR_VERTEX_CREDENCIALES=/run/secretos/vertex.json` y `EXTRACTOR_PROXY_CAEXTRA=/run/secretos/ca-proxy.pem` como ejemplos.
- README:
  - Primer párrafo: "los datos van solo al proveedor de IA que la organización configure; con Ollama no salen de la infraestructura".
  - Sección "Elegir proveedor de IA" con los tres casos (variables mínimas, `COMPOSE_PROFILES=ollama` solo para Ollama), cómo crear la key de Azure y la cuenta de servicio de Vertex (rol `Vertex AI User`, archivo en `secretos/`), modelos de razonamiento de Azure (`EXTRACTOR_AZURE_RAZONAMIENTO=true`, subir `EXTRACTOR_AZURE_MAXTOKENS`, usar un `api-version` que acepte `reasoning_effort`), presupuesto de razonamiento de Gemini, advertencia de `global`, proxy y CA.
  - Tabla de errores: 503 también por cuota agotada (con `Retry-After`) y por credenciales/proxy rechazados.

- [ ] **Step 1:** Editar los archivos.
- [ ] **Step 2: Verificar.** Si hay Docker Compose disponible: `docker compose config` sin y con `COMPOSE_PROFILES=ollama` → sin errores, `ollama` solo aparece con el perfil. En la notebook no hay Compose (ver memoria del proyecto): dejarlo anotado como verificación manual pendiente en el reporte de la tarea. Siempre: `./scripts/mvn.sh -q test` → verde.
- [ ] **Step 3: Commit** `docs: compose, .env.example y README para elegir proveedor de IA`.

### Verificación final (manual, la hace el usuario)

- [ ] En el servidor de ODEA: `EXTRACTOR_LLM_PROVEEDOR=VERTEX_GEMINI` con la suscripción de ODEA, levantar y correr el benchmark existente (`benchmark/`); comparar precisión y tiempos con los resultados iniciales.
- [ ] Repetir con `AZURE_OPENAI` cuando ODEA contrate el recurso de prueba.
- [ ] Probar una vez detrás de un proxy con usuario sobre HTTPS real.
