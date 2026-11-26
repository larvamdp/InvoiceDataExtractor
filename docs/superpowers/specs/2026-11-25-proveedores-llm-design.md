# Proveedor de IA configurable (Ollama, Azure OpenAI, Vertex Gemini) — diseño

Fecha: 2026-11-25 · Tamaño: proyecto

## Por qué

Hoy el servicio solo usa un modelo local servido por Ollama. Los clientes de ODEA, y ODEA misma, ya tienen
proveedores de IA contratados: lo más probable es Azure (acuerdo corporativo con Microsoft); ODEA tiene suscripción a
Google Vertex. Si el servicio usa el proveedor de la propia organización, la confidencialidad queda resuelta: los
documentos solo van al modelo que esa organización ya contrató. Además se elimina el problema de rendimiento en
servidores sin GPU, que hoy hace inviable el modelo local en CPU (504 en casi todo, ver README).

## Objetivos, no objetivos, anti-objetivos

- **Objetivos**
  - Elegir el proveedor por configuración de arranque de cada instalación: `OLLAMA`, `AZURE_OPENAI`, `VERTEX_GEMINI`.
  - Misma calidad de comportamiento ante fallas que el cliente Ollama actual: timeout → 504, tope de tokens → 422,
    proveedor caído → 503, logs sin contenido del documento.
  - Que agregar otro proveedor más adelante (Claude, por cualquiera de sus vías) sea sumar una implementación de
    `LlmCliente` sin tocar el resto.
- **No objetivos**
  - Elegir proveedor o modelo por solicitud; credenciales en la solicitud; multi-tenant. Cada organización despliega su
    propia instancia (decisión del usuario).
  - Claude en esta versión.
  - Autenticación por identidad administrada (Managed Identity / cuenta de servicio adjunta) ni service principal de
    Entra ID: el servicio corre en servidores propios (de clientes y de ODEA), fuera de las nubes. Se agrega cuando un
    cliente lo exija.
  - Reintentos automáticos ante cuota agotada (429), fallback entre proveedores, streaming.
- **Anti-objetivos** (sería un fracaso aunque funcione)
  - Cambiar el contrato de la API (`/api/v1/extracciones/sync`, formato de respuesta, códigos de error).
  - Que algún dato del documento o credencial aparezca en logs o en mensajes de error.
  - Romper instalaciones existentes con Ollama: las variables `EXTRACTOR_OLLAMA_*` siguen valiendo igual.
  - Perder la conciliación con el QR porque un proveedor reordena u omite `clavesQr`.

## Restricciones

- Spring Boot 3.5 / Java 21, sin frameworks nuevos de IA.
- Despliegue con Docker Compose en servidores propios. Con proveedor en la nube, `extractor-api` necesita salida a
  internet (hoy la tiene por la red `publica`); el contenedor `ollama` no debe levantarse.
- Autenticación: Azure con **API key**; Vertex con **archivo JSON de cuenta de servicio** montado en el contenedor
  (decisión del usuario).
- Los servidores de los clientes salen a internet **a través de un proxy corporativo** (dato del usuario): todo el
  tráfico hacia Azure y Google, incluida la obtención del token de Vertex, debe poder pasar por un proxy.

## Enfoques considerados

1. **Un cliente propio por proveedor con `RestClient` (elegido por el usuario).** `AzureOpenAiLlmCliente` y
   `VertexGeminiLlmCliente` al estilo del `OllamaLlmCliente` actual: cuerpo armado a mano, salida estructurada nativa
   de cada proveedor, mapeo fino de errores. Única dependencia nueva: `com.google.auth:google-auth-library-oauth2-http`
   para obtener el token de Vertex. Más código propio, control total.
2. **Spring AI.** Abstracción común con starters para los tres. Menos código, pero suma los SDK de Azure y Google
   (gRPC, imagen pesada), ata el proyecto a las versiones de Spring AI y esconde los detalles de falla (corte por
   tokens, timeouts, filtros) que hoy se mapean con cuidado. Descartado.
3. **Un solo cliente "formato OpenAI".** Azure lo habla nativo y Vertex tiene un endpoint compatible para Gemini. Las
   capas de compatibilidad van atrasadas en salida estructurada e imágenes, y Ollama por esa vía no permite fijar
   `num_ctx`, así que su cliente nativo quedaría igual. Descartado.

## Diseño

### Selección del proveedor

- Nueva propiedad `extractor.llm.proveedor` (`EXTRACTOR_LLM_PROVEEDOR`), enum `OLLAMA | AZURE_OPENAI | VERTEX_GEMINI`,
  default `OLLAMA` (instalaciones existentes no cambian).
- Se registra un único bean `LlmCliente` según el proveedor (p. ej. `@ConditionalOnProperty` o una fábrica en una
  `@Configuration`). `ExtraccionService` no cambia: sigue recibiendo un `LlmCliente`.
- **Validación al arrancar:** si falta configuración obligatoria del proveedor elegido (endpoint, key, proyecto,
  archivo de credenciales inexistente o ilegible, modelo), la aplicación no arranca y el log dice qué variable falta,
  sin mostrar valores secretos. No se hace una llamada de prueba al proveedor al arrancar.
- Al arrancar se loguea `proveedor`, `modelo` y endpoint/región (nunca la key).

### Configuración

Las propiedades de Ollama quedan como están (`extractor.ollama.*`). Cada proveedor tiene su bloque propio:

| Propiedad (variable) | Azure OpenAI | Obligatoria | Default |
|---|---|---|---|
| `extractor.azure.endpoint` (`EXTRACTOR_AZURE_ENDPOINT`) | `https://<recurso>.openai.azure.com` | sí | — |
| `extractor.azure.deployment` (`EXTRACTOR_AZURE_DEPLOYMENT`) | nombre del deployment (modelo con visión, p. ej. GPT‑4.1 / GPT‑4o) | sí | — |
| `extractor.azure.api-key` (`EXTRACTOR_AZURE_APIKEY`) | API key del recurso | sí | — |
| `extractor.azure.api-version` (`EXTRACTOR_AZURE_APIVERSION`) | versión de API con structured outputs | no | una GA vigente al implementar |
| `extractor.azure.timeout` | timeout de lectura | no | `90s` |
| `extractor.azure.max-tokens` | tope de salida (`max_completion_tokens`) | no | `4096` |
| `extractor.azure.razonamiento` (`EXTRACTOR_AZURE_RAZONAMIENTO`) | `true` si el deployment es un modelo de razonamiento (familia gpt‑5, serie o) | no | `false` |

| Propiedad (variable) | Vertex Gemini | Obligatoria | Default |
|---|---|---|---|
| `extractor.vertex.proyecto` (`EXTRACTOR_VERTEX_PROYECTO`) | ID del proyecto GCP | sí | — |
| `extractor.vertex.region` (`EXTRACTOR_VERTEX_REGION`) | región (p. ej. `us-central1`, `southamerica-east1`) o `global` | sí | — |
| `extractor.vertex.modelo` (`EXTRACTOR_VERTEX_MODELO`) | modelo Gemini (p. ej. `gemini-2.5-flash`) | sí | — |
| `extractor.vertex.credenciales` (`EXTRACTOR_VERTEX_CREDENCIALES`) | ruta al JSON de la cuenta de servicio dentro del contenedor | sí | — |
| `extractor.vertex.timeout` | timeout de lectura | no | `90s` |
| `extractor.vertex.max-tokens` | tope de salida (`maxOutputTokens`) | no | `4096` |

- Los modelos en la nube no llevan default: se deprecan rápido y cada cliente tiene los suyos.
- El tope de salida en la nube es más alto que el de Ollama (2048) porque ahí el costo de generar no es el problema;
  sigue existiendo para cortar respuestas desbocadas.
- **Proxy de salida** (aplica solo a Azure y Vertex; Ollama sigue directo por la red interna):

  | Propiedad (variable) | Qué es | Obligatoria | Default |
  |---|---|---|---|
  | `extractor.proxy.host` (`EXTRACTOR_PROXY_HOST`) | host del proxy; vacío = sin proxy | no | — |
  | `extractor.proxy.puerto` (`EXTRACTOR_PROXY_PUERTO`) | puerto | si hay host | — |
  | `extractor.proxy.usuario` (`EXTRACTOR_PROXY_USUARIO`) | usuario (autenticación Basic) | no | — |
  | `extractor.proxy.clave` (`EXTRACTOR_PROXY_CLAVE`) | clave | si hay usuario | — |

  - Se usa en el `HttpClient` de los dos clientes nuevos y en el transporte HTTP de `google-auth-library` (fábrica de
    transporte con el mismo proxy al cargar las credenciales), para que el token de Vertex también pase por el proxy.
  - Ojo: el `HttpClient` del JDK tiene deshabilitado Basic para el túnel HTTPS
    (`jdk.http.auth.tunneling.disabledSchemes`); con usuario configurado hay que habilitarlo explícitamente.
  - Un 407 del proxy se mapea a 503 con log "el proxy rechazó la autenticación"; un fallo de conexión al proxy, con
    log que nombre al proxy, no al proveedor.
  - **Certificado de la empresa** (decisión del diseñador: los proxies corporativos suelen inspeccionar TLS con una CA
    propia): `EXTRACTOR_CA_EXTRA` apunta a un archivo PEM montado en el contenedor; si está definido, se agrega a las
    CA de confianza de los clientes en la nube (y del transporte de Google), además de las del JDK.
  - No hay lista de exclusiones (`no_proxy`): los únicos destinos que usan el proxy son el proveedor elegido y Google
    OAuth.

- **Concurrencia:** `extractor.concurrencia.max-llm` pasa a ser opcional; si no se define vale `1` con Ollama y `4`
  con proveedores en la nube. Si se define, manda el valor configurado.

### Contrato común de los clientes

Los tres clientes implementan `LlmCliente.completar(PromptLlm)` y devuelven el JSON de respuesta como texto. Para todos:

| Situación del proveedor | Excepción | HTTP de la API |
|---|---|---|
| Timeout de lectura | `LlmTimeoutException` | 504 |
| Respuesta cortada por tope de tokens (`finish_reason: length` / `finishReason: MAX_TOKENS`) | `LlmLimiteTokensException` (mensaje nombra la variable del proveedor) | 422, sin reintento |
| 429 (cuota / rate limit) | nueva `LlmCuotaAgotadaException` | 503 "Se alcanzó el límite de uso del proveedor de IA." + `Retry-After` si el proveedor lo envió |
| Bloqueo por filtro de contenido/seguridad (Azure `content_filter`; Gemini `SAFETY`, `RECITATION`, `PROHIBITED_CONTENT`, prompt bloqueado) | `RespuestaLlmInvalidaException` | 422 (el servicio reintenta una vez, como hoy) |
| 401/403 (credenciales o permisos) | `LlmNoDisponibleException` | 503; el log dice "credenciales rechazadas por el proveedor" con el status, para que se distinga de una caída |
| Otros 4xx/5xx, conexión rechazada, cuerpo ilegible | `LlmNoDisponibleException` | 503 |

- Logs: igual que Ollama, solo proveedor, modelo, cantidad de imágenes, duración y tokens de entrada/salida que
  informe el proveedor (`usage` / `usageMetadata`). Nunca prompt, respuesta, key ni token. `extractor.debug.log-prompts`
  aplica igual a los tres.
- Temperatura 0 en los tres.
- `modelo()` devuelve el deployment (Azure) o el modelo (Vertex); `metadatos.modelo` de la respuesta no cambia de forma.

### Azure OpenAI

- `POST {endpoint}/openai/deployments/{deployment}/chat/completions?api-version=…`, header `api-key`.
- Mensajes `system` y `user`; las imágenes van como partes `image_url` con data URL (`data:image/png;base64,…`).
- Salida estructurada con `response_format: {type: "json_schema", json_schema: {name, strict: true, schema}}`.
- Contenido en `choices[0].message.content`; un `message.refusal` no nulo se trata como filtro de contenido.
- **Modelos de razonamiento** (decisión del diseñador: un cliente corporativo puede tener solo la familia gpt‑5
  disponible): con `extractor.azure.razonamiento=true` no se envía `temperature` (esos modelos la rechazan) y se envía
  `reasoning_effort` en el mínimo que acepte el modelo. Los tokens de razonamiento cuentan dentro de
  `max_completion_tokens`; el README recomienda subir el tope en ese caso. Un 400 de Azure se loguea con su `code` de
  error (sin el mensaje, que puede contener datos) para que un parámetro rechazado no se confunda con una caída.

### Vertex Gemini

- `POST https://{region}-aiplatform.googleapis.com/v1/projects/{proyecto}/locations/{region}/publishers/google/models/{modelo}:generateContent`,
  header `Authorization: Bearer <token>`.
- **Ubicación `global`** (decisión del usuario: se puede elegir una región o `global`): el host pasa a ser
  `aiplatform.googleapis.com` y la ubicación en la ruta `locations/global`. Al arrancar con `global` se loguea una
  advertencia, y el README aclara que con `global` Google puede procesar los datos en cualquier región; para
  garantizar dónde se procesan, elegir una región concreta.
- Token: `GoogleCredentials.fromStream(json).createScoped("https://www.googleapis.com/auth/cloud-platform")`,
  `refreshIfExpired()` antes de cada llamada (la librería cachea el token). La obtención del token se aísla detrás de
  una interfaz para poder sustituirla en los tests.
- `systemInstruction` para el prompt de sistema; `contents[0].parts` con el texto y las imágenes como `inlineData`
  (`mimeType`, `data`).
- `generationConfig`: `temperature: 0`, `maxOutputTokens`, `responseMimeType: "application/json"` y el esquema.
- Contenido en `candidates[0].content.parts[*].text` (concatenar). Para modelos con razonamiento, excluir partes
  marcadas como `thought` y fijar el presupuesto de razonamiento al mínimo que el modelo permita.

### Esquema de respuesta por proveedor

`EsquemaRespuesta` sigue generando el esquema canónico de hoy. Cada cliente lo adapta a lo que su proveedor acepta
con una función pura y testeada, sin cambiar el formato que el parser espera. Diferencias conocidas a resolver:

- **Azure (strict):** exige `additionalProperties: false` en cada objeto y que todas las propiedades sean requeridas.
  Hoy las propiedades de `clavesQr` no son requeridas: pasan a requeridas con tipo arreglo-o-null, y el parser trata
  null igual que ausente. `maxItems: 0` (detalle sin campos) no está soportado en strict: resolver con otra
  construcción equivalente (p. ej. ítems sin propiedades) y aceptar un arreglo vacío.
- **Gemini:** usar el esquema JSON (`responseJsonSchema`) si el modelo configurado lo soporta; si no, el subconjunto
  OpenAPI (`responseSchema`, con `nullable` en vez de `type: [..., "null"]`). **El orden importa:** `clavesQr` debe
  generarse antes de `detalle` (ver diseño de salida compacta), así que el esquema debe fijar el orden
  `cabecera, clavesQr, detalle` (`propertyOrdering` en el subconjunto OpenAPI).
- Ollama sigue recibiendo el esquema canónico sin cambios.

### Docker Compose y documentación

- El servicio `ollama` pasa a un perfil de Compose (`ollama`); con proveedor en la nube no se levanta. Con Ollama se
  usa `COMPOSE_PROFILES=ollama` (en `.env`). La dependencia de `extractor-api` sobre `ollama` no debe impedir arrancar
  sin ese perfil.
- `extractor-api` reenvía las nuevas variables; el JSON de Vertex se monta como volumen de solo lectura (ruta
  documentada en `.env.example`).
- `.env.example` con un bloque por proveedor. README: sección "Elegir proveedor de IA" con los tres casos, cómo crear
  la key / cuenta de servicio (rol mínimo `Vertex AI User`), y el cambio de la promesa de confidencialidad: "los datos
  van solo al proveedor de IA que la organización configure; con Ollama no salen de la infraestructura".

### Pruebas

- Por cliente nuevo, con WireMock como `OllamaLlmClienteTest`: cuerpo enviado (mensajes, imágenes, esquema,
  temperatura, tope), extracción del contenido, y cada fila de la tabla de errores.
- Tests unitarios de los adaptadores de esquema (casos con/sin QR, detalle vacío).
- Selección de bean y validación de arranque por proveedor (configuración faltante → no arranca).
- Concurrencia por defecto según proveedor.
- Proxy: WireMock (u otro proxy de prueba) actuando como proxy, verificando que las llamadas al proveedor y al
  endpoint de token pasan por él, con y sin usuario; 407 → 503. URL de Vertex con región y con `global`.
- `LogsSinContenidoTest` extendido a los clientes nuevos (ni contenido ni key ni token en logs).
- Prueba real (manual): ODEA corre el benchmark existente contra Vertex Gemini con su suscripción, y contra Azure
  cuando contrate el recurso de prueba. El benchmark llama a la API, así que no necesita cambios para esto.

## Queda a criterio del implementador

- Nombres de clases, del enum y de la nueva excepción; mecanismo exacto de selección del bean.
- Versión concreta de `api-version` de Azure y de `google-auth-library`.
- Si Gemini usa `responseJsonSchema` o `responseSchema` según lo que soporte el modelo de prueba, siempre que se
  respete el orden de `clavesQr`.
- `detail` de imagen en Azure (`high`/`auto`) según precisión observada en el benchmark.
- Cómo resolver `depends_on` con perfiles de Compose.
