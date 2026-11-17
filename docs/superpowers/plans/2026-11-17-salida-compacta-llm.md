# Salida compacta del LLM y tope de generación — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Acotar la generación del modelo (tope de tokens, error 422 sin reintento) y reducir lo que escribe (valores directos, sin `descripcion`).

**Architecture:** El tope vive en `OllamaLlmCliente` (envía `options.num_predict`, detecta `done_reason: "length"`). La salida compacta cambia solo el contrato interno modelo↔Java (`EsquemaRespuesta`, `ConstructorPrompt`, `ParserRespuestaLlm`); `ParserRespuestaLlm` sigue produciendo `RespuestaLlm`/`ValorLlm`, así que conciliación, verificación y API no cambian.

**Tech Stack:** Java 21, Spring Boot 3.5, Jackson, JUnit 5 + AssertJ + WireMock. Build: `./scripts/mvn.sh` (Maven en Docker).

**Spec:** `docs/superpowers/specs/2026-11-17-salida-compacta-llm-design.md`

## Global Constraints

- Contrato de la API sin cambios: cada campo sigue saliendo como `{valor, descripcion, fuente}`.
- `extractor.ollama.num-predict` / `EXTRACTOR_OLLAMA_NUMPREDICT`, default `2048`, enviado como `options.num_predict`.
- `done_reason: "length"` → HTTP **422**, mensaje exacto: `La respuesta del modelo superó el límite de tokens (EXTRACTOR_OLLAMA_NUMPREDICT).` Sin reintento.
- Sin `repeat_penalty` ni `presence_penalty`.
- Logs y mensajes de error nunca incluyen contenido del documento ni de la respuesta del modelo (solo metadatos como `eval_count`).
- Ausente → `ValorLlm(null, "Dato no encontrado.", List.of())` (constante `ParserRespuestaLlm.NO_ENCONTRADO`).

## Review Focus

- El modelo devuelve un número JSON en vez de string (`"importeTotal": 12100.5`) → se acepta como texto `"12100.5"`.
- El modelo devuelve un objeto o arreglo como valor (vuelve al formato viejo) → el campo queda "Dato no encontrado.", sin excepción.
- El modelo escribe el literal `"null"` o `""` como string → "Dato no encontrado.".
- `clavesQr` con campos inexistentes, campos de detalle, claves fuera de `DatosQrArca.CLAVES`, o presente sin QR → se ignora lo inválido, sin excepción.
- `done_reason: "length"` con contenido que igual parsea como JSON → igual es 422 (el JSON truncado-pero-válido no es confiable).

---

### Task 1: Tope de generación (`num_predict`) y 422 sin reintento

**Files:**
- Modify: `src/main/java/ar/comprobantes/extractor/config/ExtractorProperties.java` (record `Ollama`)
- Create: `src/main/java/ar/comprobantes/extractor/error/LlmLimiteTokensException.java`
- Modify: `src/main/java/ar/comprobantes/extractor/llm/OllamaLlmCliente.java` (`armarCuerpo`, `completar`)
- Modify: `docker-compose.yml`, `.env.example`, `README.md` (tabla de variables y lista de errores)
- Test: `src/test/java/ar/comprobantes/extractor/config/ExtractorPropertiesTest.java`, `src/test/java/ar/comprobantes/extractor/llm/OllamaLlmClienteTest.java`, `src/test/java/ar/comprobantes/extractor/servicio/ExtraccionServiceTest.java`, `src/test/java/ar/comprobantes/extractor/api/ExtraccionControllerTest.java`

**Interfaces:**
- Produces: `ExtractorProperties.Ollama(String url, String modelo, Duration timeout, int numCtx, Boolean think, int numPredict)` con `@DefaultValue("2048") int numPredict` (último componente).
- Produces: `public class LlmLimiteTokensException extends ExtraccionException` — `status()` = `UNPROCESSABLE_ENTITY`, `titulo()` = `"Respuesta del modelo inválida"`, constructor `(String mensaje)`. **No** extiende `RespuestaLlmInvalidaException`, por eso `ExtraccionService.consultarLlm` no la reintenta (no se modifica el servicio).

- [ ] **Step 1: Tests que fallan**

  - `ExtractorPropertiesTest.defaults`: agregar `assertThat(p.ollama().numPredict()).isEqualTo(2048);`
  - `OllamaLlmClienteTest`: el helper `cliente(...)` pasa `numPredict` `2048` al record. En `enviaCuerpoEsperado` agregar `.withRequestBody(matchingJsonPath("$.options.num_predict", equalTo("2048")))` y `.withRequestBody(notMatching("(?s).*(repeat_penalty|presence_penalty).*"))`.
  - `OllamaLlmClienteTest.limiteDeTokensEs422`: stub `okJson("{\"message\":{\"content\":\"{\\\"cabecera\\\":{\"},\"done_reason\":\"length\",\"eval_count\":2048}")` → `assertThatThrownBy(...).isInstanceOf(LlmLimiteTokensException.class).hasMessage("La respuesta del modelo superó el límite de tokens (EXTRACTOR_OLLAMA_NUMPREDICT).")`.
  - `OllamaLlmClienteTest.limiteDeTokensConJsonValidoIgualEs422`: igual pero `content` = `"{}"` y `done_reason` `"length"` → misma excepción.
  - `OllamaLlmClienteTest.doneReasonStopDevuelveContenido`: `done_reason` `"stop"` → devuelve el content.
  - `ExtraccionServiceTest.noReintentaSiSeAlcanzaElLimite`: el `LlmCliente` falso lanza `new LlmLimiteTokensException("límite")` → `assertThatThrownBy(...).isInstanceOf(LlmLimiteTokensException.class)` y `assertThat(llamadas.get()).isEqualTo(1)`. (Ajustar el helper `servicio` para aceptar un `LlmCliente` o una función que pueda lanzar.)
  - `ExtraccionControllerTest.limiteDeTokensEs422`: mock `thenThrow(new LlmLimiteTokensException("La respuesta del modelo superó el límite de tokens (EXTRACTOR_OLLAMA_NUMPREDICT)."))` → `status().is(422)` y `jsonPath("$.detail")` con ese texto.

- [ ] **Step 2: Correr y ver que fallan**

  Run: `./scripts/mvn.sh -q test -Dtest='ExtractorPropertiesTest,OllamaLlmClienteTest,ExtraccionServiceTest,ExtraccionControllerTest'`
  Expected: FAIL de compilación (`numPredict`, `LlmLimiteTokensException` inexistentes).

- [ ] **Step 3: Implementar**

  - `ExtractorProperties.Ollama`: agregar `@DefaultValue("2048") int numPredict`.
  - `LlmLimiteTokensException` según Interfaces.
  - `OllamaLlmCliente.armarCuerpo`: `options` agrega `.put("num_predict", config.numPredict())`.
  - `OllamaLlmCliente.completar`: después de validar `message.content` y del log `info` existente, si `respuesta.path("done_reason").asText("")` es `"length"` → `log.warn("El modelo alcanzó el límite de tokens: modelo={}, eval_count={}, num_predict={}", ...)` y lanzar `LlmLimiteTokensException` con el mensaje exacto de Global Constraints.
  - `docker-compose.yml` (servicio `extractor-api`): `EXTRACTOR_OLLAMA_NUMPREDICT: ${EXTRACTOR_OLLAMA_NUMPREDICT:-2048}` junto a `NUMCTX`.
  - `.env.example`: `#EXTRACTOR_OLLAMA_NUMPREDICT=2048` con comentario `# Tope de tokens que escribe el modelo; si se alcanza la API responde 422.`
  - `README.md`: fila en la tabla de variables (`extractor.ollama.num-predict`, `2048`, "Tope de tokens de salida del modelo; si se alcanza, 422") y en la línea de errores: `422 JSON del LLM inválido o límite de tokens alcanzado`.

- [ ] **Step 4: Correr y ver que pasan**

  Run: `./scripts/mvn.sh -q test`
  Expected: BUILD SUCCESS (suite completa).

- [ ] **Step 5: Commit**

  ```bash
  git add -A src docker-compose.yml .env.example README.md
  git commit -m "feat: tope de tokens de salida del modelo (num_predict) con 422 sin reintento"
  ```

### Task 2: Salida compacta del modelo (valores directos + `clavesQr` aparte)

**Files:**
- Modify: `src/main/java/ar/comprobantes/extractor/llm/EsquemaRespuesta.java`
- Modify: `src/main/java/ar/comprobantes/extractor/llm/ConstructorPrompt.java` (`SISTEMA_BASE`, `SISTEMA_QR`)
- Modify: `src/main/java/ar/comprobantes/extractor/llm/ParserRespuestaLlm.java`
- Test: `src/test/java/ar/comprobantes/extractor/llm/EsquemaRespuestaTest.java`, `ConstructorPromptTest.java`, `ParserRespuestaLlmTest.java`; actualizar JSON de respuestas simuladas del LLM en `servicio/ExtraccionServiceTest.java`, `api/ExtraccionControllerTest.java`, `api/ExtraccionCompletaHttpTest.java`, `api/LogsSinContenidoTest.java`

**Interfaces:**
- Consumes: nada de Task 1.
- Produces: `EsquemaRespuesta.para(SolicitudExtraccion, boolean hayQr): ObjectNode` y `ParserRespuestaLlm.parsear(String, SolicitudExtraccion): RespuestaLlm` con las mismas firmas; solo cambia el formato JSON:

  ```json
  {"cabecera": {"<campo>": "<string>" | null},
   "detalle":  [{"<campo>": "<string>" | null}],
   "clavesQr": {"<campoCabecera>": ["<clave de DatosQrArca.CLAVES>"]}}
  ```

- [ ] **Step 1: Tests que fallan**

  - `EsquemaRespuestaTest.esquemaTienePropiedadPorCampo` (con QR): `cabecera.properties.<campo>.type` = `["string","null"]`; `cabecera.required` = todos los campos; `detalle.items.properties.<campo>.type` = `["string","null"]`; `properties.clavesQr.properties.<campoCabecera>.items.enum` = `DatosQrArca.CLAVES`; `clavesQr` no está en `required` raíz ni tiene `required` propio; ningún nodo del esquema contiene la clave `"descripcion"` ni `"valor"` (salvo que sean nombres de campos pedidos).
  - `EsquemaRespuestaTest.esquemaSinQrNoTieneClavesQr`: sin QR, `properties` raíz no tiene `clavesQr`.
  - `EsquemaRespuestaTest.esquemaSinDetalleEsArrayVacio`: sin cambios.
  - `ConstructorPromptTest.sistemaNoPideDescripciones`: `sistema()` no contiene `"descripcion"`; con QR contiene `"clavesQr"`; sin QR no.
  - `ParserRespuestaLlmTest` (reescribir con el formato nuevo):
    - `parseaRespuestaCompleta`: cabecera con 2 valores, detalle con 2 filas, `clavesQr: {"numero": ["ptoVta","nroCmp"]}` → `ValorLlm("0003-00012345", null, List.of("ptoVta","nroCmp"))`; los demás con `clavesQr` vacío y `descripcion` `null`.
    - `campoOmitidoQuedaNoEncontrado`, `campoNullQuedaNoEncontrado`, `valorBlankSeTrataComoNoEncontrado`, `literalNullSeTrataComoNoEncontrado` (`"null"`, sin distinguir mayúsculas) → `ValorLlm(null, "Dato no encontrado.", List.of())`.
    - `valorNumericoSeAceptaComoTexto`: `"importeTotal": 12100.5` → valor `"12100.5"`.
    - `valorObjetoQuedaNoEncontrado`: `"importeTotal": {"valor":"1"}` → no encontrado.
    - `clavesQrInvalidasSeFiltran`: claves fuera de `CLAVES`, duplicadas, no textuales → filtradas; `clavesQr` de un campo no pedido o que no es arreglo → ignorado.
    - `campoExtraSeIgnora`, `jsonInvalidoLanzaExcepcionSinContenido`: se mantienen con el formato nuevo.
  - Actualizar las constantes JSON simuladas en `ExtraccionServiceTest` (`JSON_OK` y demás), `ExtraccionControllerTest`, `ExtraccionCompletaHttpTest` y `LogsSinContenidoTest` al formato nuevo, conservando los mismos valores y las mismas `clavesQr` que hoy (p. ej. `"importeTotal"` con `["importe"]`), para que sus aserciones existentes sigan valiendo. Donde una aserción dependía de la `descripcion` del LLM (p. ej. `"Nro"`), cambiarla a lo que ahora arma Java.

- [ ] **Step 2: Correr y ver que fallan**

  Run: `./scripts/mvn.sh -q test -Dtest='EsquemaRespuestaTest,ConstructorPromptTest,ParserRespuestaLlmTest'`
  Expected: FAIL (esquema y parser todavía con el formato viejo).

- [ ] **Step 3: Implementar**

  - `EsquemaRespuesta`: `valor(...)` pasa a devolver `stringONull()`; `cabecera` y `detalle` usan eso para cada campo; si `hayQr`, la raíz agrega `properties.clavesQr` = objeto con una propiedad por campo de cabecera (arreglo de `enum` `DatosQrArca.CLAVES`), sin `required`. `required` raíz sigue siendo `["cabecera","detalle"]`.
  - `ConstructorPrompt.SISTEMA_BASE`: quitar las tres reglas sobre `descripcion`; la regla de ausente pasa a `Si el dato no está en el documento, devuelve null.`; agregar `Cada campo es directamente el valor (texto) o null.` Mantener las reglas de no inventar, valor tal como figura, etiquetas como pistas, filas del detalle, anti-inyección y "solo JSON".
  - `ConstructorPrompt.SISTEMA_QR`: `- Hay datos verificados del QR de ARCA. En "clavesQr" indica, solo para los campos de cabecera que correspondan, qué claves del QR les corresponden; omite los demás.`
  - `ParserRespuestaLlm`: `campos(nodo, pedidos, clavesQr)` donde `clavesQr` es `raiz.path("clavesQr").path(nombre)` solo para cabecera (detalle sin claves). Valor: textual o numérico → `asText()`, `trim`; vacío o `"null"` (ignore case) o cualquier otro tipo → no encontrado. `descripcion` siempre `null` cuando hay valor. Filtro de claves igual al actual.

- [ ] **Step 4: Correr y ver que pasan**

  Run: `./scripts/mvn.sh -q test`
  Expected: BUILD SUCCESS (suite completa).

- [ ] **Step 5: Commit**

  ```bash
  git add -A src
  git commit -m "feat: salida compacta del modelo (valores directos, clavesQr aparte, sin descripciones)"
  ```

### Task 3: Medición en la notebook

**Files:** ninguno de código. Resultado en el mensaje final al usuario (no se versiona).

- [ ] **Step 1:** `docker compose up -d --build` → ambos servicios `Up`; `curl` GET a `/api/v1/extracciones/sync` responde 405.
- [ ] **Step 2:** Elegir un documento sintético de texto de `benchmark/dataset/` (si la carpeta no existe, generarla con el comando de `README.md` → "Cómo medir"). Enviarlo con `curl` usando su `solicitud.json`, `--max-time 1000`, guardando la salida en el scratchpad.
- [ ] **Step 3:** Registrar código HTTP, `metadatos.tiemposMs.llm` y `total`, y del log `Ollama completó` el `eval_count` (comparar con los ~7.300 tokens previos). Comparar valores contra `esperado.json` del documento.
- [ ] **Step 4:** Informar al usuario: tiempos, `eval_count`, aciertos; si supera ~60 s, mencionar la opción 3 (modelo de texto liviano) como siguiente paso.
