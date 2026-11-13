# Invoice Data Extractor — Iteración 1 — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Servicio REST sincrónico que extrae campos de cabecera y detalle de comprobantes argentinos (PDF/imagen) usando un LLM local (Ollama), con QR de ARCA, dockerizado, y un set sintético + benchmark para medir tiempos y precisión.

**Architecture:** Spring Boot con un pipeline en memoria: detección → QR ARCA (ZXing) → texto (PDFBox) o imágenes → LLM vía HTTP a Ollama con salida JSON forzada por schema → normalización por tipo, conciliación con QR y verificación textual en Java. Un semáforo limita las llamadas concurrentes al LLM. docker-compose levanta API + Ollama.

**Tech Stack:** Java 21, Spring Boot 3.5.x (último patch), Maven, PDFBox 3.0.x, ZXing 3.5.x (`core` + `javase`), Jackson, Spring `RestClient` contra la API HTTP de Ollama, JUnit 5, AssertJ, WireMock 3.x (tests), Docker Compose, imagen `ollama/ollama`.

**Spec:** `docs/superpowers/specs/2026-11-13-invoice-extractor-design.md`

## Global Constraints

- Paquete raíz: `ar.comprobantes.extractor`. Código, mensajes y descripciones al usuario en **español**.
- Endpoint: `POST /api/v1/extracciones/sync`, `multipart/form-data`, partes `documento` (archivo) y `solicitud` (JSON).
- Tipos de campo: `TEXTO | NUMERO | IMPORTE | FECHA | CUIT` (default `TEXTO`). Fuente: `QR_ARCA | DOCUMENTO | null`. Modo: `TEXTO | IMAGEN`.
- Normalización: `FECHA` → `yyyy-MM-dd`; `IMPORTE`/`NUMERO` → `BigDecimal` con punto (`1.234,56` → `1234.56`); `CUIT` → 11 dígitos sin guiones, dígito verificador validado (si falla se devuelve igual y se informa).
- Dato ausente: `valor: null`, `descripcion: "Dato no encontrado."`. Nunca inventar.
- Límites por defecto: 10 MB, 10 páginas. Timeout LLM 90 s. Espera máxima en cola 60 s. Concurrencia LLM 1.
- Errores en RFC 7807 (`ProblemDetail`): 400 inválido, 413 tamaño/páginas, 415 formato, 422 JSON del LLM inválido tras 1 reintento, 503 ocupado/Ollama caído, 504 timeout LLM.
- **Confidencialidad:** nada del documento, prompt ni respuesta del LLM en logs ni en disco. PDFBox solo en memoria. Flag `extractor.debug.log-prompts` default `false`. Sin llamadas a servicios externos.
- Toda configuración bajo `extractor.*` y sobreescribible por variables de entorno.
- **Compilar/testear sin JDK local:** `./scripts/mvn.sh <args>` (Maven dentro de `maven:3.9-eclipse-temurin-21`, montando `~/.m2`). Si hay JDK 21 local, `mvn <args>` es equivalente.
- Desvío del diseño (decisión del plan): se usa `RestClient` directo contra `/api/chat` de Ollama en lugar de LangChain4j, para controlar exactamente `format` (JSON schema), `images`, `think` y timeouts, y poder mockear a nivel HTTP con WireMock.

## Review Focus

1. **PDF corrupto o protegido con contraseña** → 400 con mensaje claro, no 500. (Test en Task 4.)
2. **Foto de celular grande (p. ej. 6000×4000 px)** → se redimensiona a `maxLadoPx` antes de enviarla al LLM, sin agotar memoria. (Test en Task 4.)
3. **Nombres de campo con espacios, acentos o duplicados** (`"número factura"`) → 400 explicando el formato permitido, porque el nombre se usa como clave del JSON schema. (Test en Task 9.)
4. **El LLM omite campos pedidos o agrega campos no pedidos** → los omitidos salen `null` + "Dato no encontrado.", los extra se ignoran. (Test en Task 5.)
5. **Contenido del documento filtrado a logs vía mensajes de excepción** (p. ej. error de parseo que incluye la respuesta del LLM) → los logs solo contienen metadatos. (Test en Task 9.)

---

## Estructura de archivos

```
pom.xml
scripts/mvn.sh                      # Maven en Docker
scripts/descargar-modelo.sh         # ollama pull en contenedor temporal
Dockerfile
docker-compose.yml
docker-compose.gpu.yml
README.md
src/main/resources/application.yml
src/main/java/ar/comprobantes/extractor/
  ExtractorApplication.java
  config/ExtractorProperties.java
  api/ExtraccionController.java
  api/ManejadorErrores.java
  api/dto/{SolicitudExtraccion, CampoSolicitado, TipoCampo, ResultadoExtraccion,
           ValorCampo, Fuente, QrArcaResultado, Metadatos, ModoProcesamiento}.java
  api/ValidadorSolicitud.java
  error/{ExtraccionException (abstracta, con HttpStatus), DocumentoInvalidoException,
         FormatoNoSoportadoException, DocumentoDemasiadoGrandeException,
         ServicioOcupadoException, LlmNoDisponibleException, LlmTimeoutException,
         RespuestaLlmInvalidaException}.java
  normalizacion/{Normalizador, ValidadorCuit, ResultadoNormalizacion}.java
  qr/{DatosQrArca, DecodificadorQrArca, LectorQrArca}.java
  documento/{TipoDocumento, DetectorTipoDocumento, DocumentoProcesado, ProcesadorDocumento}.java
  llm/{ConstructorPrompt, EsquemaRespuesta, PromptLlm, RespuestaLlm, ValorLlm,
       ParserRespuestaLlm, LlmCliente, OllamaLlmCliente}.java
  conciliacion/{ConciliadorQr, VerificadorTextual}.java
  servicio/{ExtraccionService, LimitadorConcurrencia}.java
src/test/java/ar/comprobantes/extractor/... (espejo de main)
src/test/java/ar/comprobantes/extractor/sintetico/{GeneradorComprobantes, DisenoComprobante, ...}.java
src/test/java/ar/comprobantes/extractor/benchmark/BenchmarkRunner.java
benchmark/dataset/            # generado, versionado (datos ficticios)
benchmark/reales/             # en .gitignore
benchmark/resultados/         # en .gitignore
```

---

### Task 1: Proyecto base, configuración y DTOs de la API

**Files:**
- Create: `pom.xml`, `scripts/mvn.sh`, `.gitignore`, `src/main/resources/application.yml`, `ExtractorApplication.java`, `config/ExtractorProperties.java`, todos los `api/dto/*.java`
- Test: `src/test/java/ar/comprobantes/extractor/api/dto/SerializacionDtoTest.java`, `config/ExtractorPropertiesTest.java`

**Interfaces:**
- Produces:
  - `enum TipoCampo { TEXTO, NUMERO, IMPORTE, FECHA, CUIT }`; `enum Fuente { QR_ARCA, DOCUMENTO }`; `enum ModoProcesamiento { TEXTO, IMAGEN }`
  - `record CampoSolicitado(String nombre, List<String> etiquetas, String descripcion, TipoCampo tipo)` — `tipo()` nunca null (constructor compacto: null → `TEXTO`; `etiquetas` null → `List.of()`).
  - `record SolicitudExtraccion(List<CampoSolicitado> camposCabecera, List<CampoSolicitado> camposDetalle)` — listas null → `List.of()`.
  - `record ValorCampo(Object valor, String descripcion, Fuente fuente)` con fábrica `static ValorCampo noEncontrado()` → `(null, "Dato no encontrado.", null)`.
  - `record QrArcaResultado(boolean presente, DatosQrArca datos, String descripcion)`. El record `qr/DatosQrArca` (solo campos, ver Task 3) se crea en esta task; Task 3 le agrega `CLAVES`, `CLAVES_CODIGO` y `valorDeClave`.
  - `record Metadatos(String tipoDocumento, int paginas, ModoProcesamiento modoProcesamiento, boolean verificacionTextual, String modelo, Map<String, Long> tiemposMs)`
  - `record ResultadoExtraccion(Map<String, ValorCampo> cabecera, List<Map<String, ValorCampo>> detalle, QrArcaResultado qrArca, Metadatos metadatos)` — mapas `LinkedHashMap` en orden de solicitud.
  - `@ConfigurationProperties("extractor") record ExtractorProperties(...)` con sub-records y defaults:
    - `ollama.url=http://localhost:11434`, `ollama.modelo=qwen3-vl:4b`, `ollama.timeout=90s`, `ollama.numCtx=8192`, `ollama.think` (Boolean, null = no enviar)
    - `concurrencia.maxLlm=1`, `concurrencia.esperaMaxima=60s`
    - `documento.maxPaginas=10`, `documento.dpiImagen=150`, `documento.dpiQr=200`, `documento.maxLadoPx=2000`, `documento.umbralCaracteresPorPagina=50`
    - `debug.logPrompts=false`
  - `application.yml`: `spring.servlet.multipart.max-file-size=10MB`, `max-request-size=11MB`; logging nivel INFO.

- [ ] **Step 1: `scripts/mvn.sh`** — `docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/app -v "$HOME/.m2":/var/maven/.m2 -e MAVEN_CONFIG=/var/maven/.m2 -w /app maven:3.9-eclipse-temurin-21 mvn -Duser.home=/var/maven "$@"`; `chmod +x`. `.gitignore`: `target/`, `benchmark/reales/`, `benchmark/resultados/`.
- [ ] **Step 2: `pom.xml`** con `spring-boot-starter-parent` 3.5.x, Java 21, dependencias: `spring-boot-starter-web`, `spring-boot-starter-validation`, `pdfbox` 3.0.x, `com.google.zxing:core` y `javase` 3.5.x; test: `spring-boot-starter-test`, `org.wiremock:wiremock-standalone` 3.x. Plugin `exec-maven-plugin` (para el benchmark, Task 12).
- [ ] **Step 3: Write failing tests**

```java
@Test void campoSinTipoEsTexto() {
    var c = new CampoSolicitado("nro", null, null, null);
    assertThat(c.tipo()).isEqualTo(TipoCampo.TEXTO);
    assertThat(c.etiquetas()).isEmpty();
}
@Test void valorNoEncontradoSerializaNulls() throws Exception {
    String json = new ObjectMapper().writeValueAsString(ValorCampo.noEncontrado());
    assertThat(json).isEqualTo("{\"valor\":null,\"descripcion\":\"Dato no encontrado.\",\"fuente\":null}");
}
@Test void deserializaSolicitudDelDiseno() { /* JSON del §7.2 del spec → 3 cabecera, 3 detalle, tipos correctos */ }
// ExtractorPropertiesTest (@SpringBootTest o ApplicationContextRunner): defaults == valores listados arriba;
// EXTRACTOR_OLLAMA_MODELO=qwen3-vl:8b sobreescribe modelo.
```

- [ ] **Step 4:** `./scripts/mvn.sh -q test` → FAIL (clases inexistentes).
- [ ] **Step 5:** Implementar DTOs, properties (`@EnableConfigurationProperties` o `@ConfigurationPropertiesScan`), `application.yml`.
- [ ] **Step 6:** `./scripts/mvn.sh -q test` → PASS.
- [ ] **Step 7: Commit** `feat: proyecto base, configuración y DTOs de la API`

---

### Task 2: Normalización por tipo y validación de CUIT

**Files:**
- Create: `normalizacion/ValidadorCuit.java`, `normalizacion/ResultadoNormalizacion.java`, `normalizacion/Normalizador.java`
- Test: `normalizacion/ValidadorCuitTest.java`, `normalizacion/NormalizadorTest.java`

**Interfaces:**
- Produces:
  - `ValidadorCuit.esValido(String onceDigitos): boolean` — pesos `5,4,3,2,7,6,5,4,3,2`; `dv = 11 - (suma % 11)`; `11→0`; `10→inválido`.
  - `record ResultadoNormalizacion(Object valor, String observacion)` — `observacion` null si todo OK.
  - `Normalizador.normalizar(String crudo, TipoCampo tipo): ResultadoNormalizacion` (componente Spring, sin estado).
    - `TEXTO`: trim + colapsar espacios.
    - `IMPORTE`/`NUMERO`: quitar `$`, espacios, letras de moneda; el **último** `,` o `.` seguido de 1–2 dígitos al final es decimal, el resto separadores de miles; un solo separador seguido de exactamente 3 dígitos = miles. Negativos con `-` o entre paréntesis. → `BigDecimal`.
    - `FECHA`: `dd/MM/yyyy`, `d/M/yyyy`, `dd-MM-yyyy`, `dd.MM.yyyy`, `dd/MM/yy` (→ 20yy), `yyyy-MM-dd`, y `d de <mes> de yyyy` en español. → `String` ISO.
    - `CUIT`: dígitos; si no son 11 → valor crudo + observación; si DV inválido → los 11 dígitos + observación `"El CUIT no supera la validación del dígito verificador."`.
    - No interpretable → `valor = crudo`, `observacion = "No se pudo interpretar '<crudo>' como <TIPO>; se devuelve tal cual."`.
    - `crudo` null/blank → `valor null`, observación null.

- [ ] **Step 1: Write failing tests**

```java
@ParameterizedTest @CsvSource({"20123456786,true", "30712345671,true", "20123456780,false", "30712345678,false"})
void validaCuit(String cuit, boolean esperado)
@ParameterizedTest @CsvSource(delimiter='|', value={
  "1.234,56|1234.56", "$ 15.000,00|15000.00", "15.000|15000", "1,234.56|1234.56",
  "350|350", "-1.500,5|-1500.5", "(1.500,00)|-1500.00", "12,5|12.5"})
void normalizaImporte(String crudo, String esperado) {
  assertThat((BigDecimal) n.normalizar(crudo, IMPORTE).valor()).isEqualByComparingTo(esperado);
}
@ParameterizedTest @CsvSource(delimiter='|', value={
  "02/10/2026|2026-10-02", "2/10/2026|2026-10-02", "02-10-26|2026-10-02",
  "2026-10-02|2026-10-02", "2 de octubre de 2026|2026-10-02"})
void normalizaFecha(...)
@Test void cuitConGuionesSeLimpia()      // "30-71234567-1" (válido) → "30712345671", observacion null
@Test void cuitInvalidoSeDevuelveConObservacion()
@Test void fechaIninterpretableDevuelveCrudo() // "fin de mes" → valor "fin de mes", observacion contiene "como FECHA"
@Test void textoColapsaEspacios()             // "  Energía   activa " → "Energía activa"
@Test void nullDevuelveNull()
```

- [ ] **Step 2:** `./scripts/mvn.sh -q test -Dtest='ValidadorCuitTest,NormalizadorTest'` → FAIL.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** Mismo comando → PASS.
- [ ] **Step 5: Commit** `feat: normalización de valores por tipo y validación de CUIT`

---

### Task 3: QR de ARCA — decodificación y lectura en imágenes

**Files:**
- Modify: `qr/DatosQrArca.java` (agregar constantes y `valorDeClave`)
- Create: `qr/DecodificadorQrArca.java`, `qr/LectorQrArca.java`
- Test: `qr/DecodificadorQrArcaTest.java`, `qr/LectorQrArcaTest.java`

**Interfaces:**
- Produces:
  - `record DatosQrArca(Integer ver, String fecha, Long cuit, Integer ptoVta, Integer tipoCmp, Long nroCmp, BigDecimal importe, String moneda, BigDecimal ctz, Integer tipoDocRec, Long nroDocRec, String tipoCodAut, Long codAut)` con:
    - `static final List<String> CLAVES` (los 13 nombres en ese orden), `static final Set<String> CLAVES_CODIGO = {"tipoCmp","moneda","tipoDocRec","tipoCodAut"}`
    - `Object valorDeClave(String clave)`
  - `DecodificadorQrArca.decodificar(String contenidoQr): Optional<DatosQrArca>` — acepta host terminado en `afip.gob.ar` o `arca.gob.ar`, path que contenga `/fe/qr`, parámetro `p`; Base64 estándar o URL-safe, con o sin padding; JSON con claves desconocidas ignoradas. Cualquier otra cosa → `Optional.empty()`.
  - `LectorQrArca.leer(List<BufferedImage> paginas): QrArcaResultado` — ZXing `MultiFormatReader` con `POSSIBLE_FORMATS=QR_CODE`, `TRY_HARDER`; `GenericMultipleBarcodeReader` por página (puede haber más de un QR); primer contenido que decodifique como ARCA gana. Resultados:
    - ninguno → `(false, null, "No se encontró código QR de ARCA.")`
    - QR presente(s) pero ninguno ARCA → `(false, null, "Se encontró un código QR pero no corresponde a ARCA o no pudo decodificarse.")`
    - OK → `(true, datos, null)`

- [ ] **Step 1: Write failing tests**

```java
// helper de test: urlQr(json) = "https://www.afip.gob.ar/fe/qr/?p=" + Base64(json)
@Test void decodificaUrlAfip()      // json del spec §7.2 → cuit 30712345671L, ptoVta 3, nroCmp 12345L, importe 15000.0
@Test void aceptaDominioArcaYBase64UrlSafeSinPadding()
@Test void rechazaOtroDominio()     // "https://ejemplo.com/fe/qr/?p=..." → empty
@Test void rechazaBase64Invalido()  // → empty, sin excepción
@Test void valorDeClave()           // datos.valorDeClave("nroCmp") == 12345L
// LectorQrArcaTest: generar QR con ZXing QRCodeWriter (300px) y pegarlo en una imagen A4 blanca a 200 DPI
@Test void encuentraQrEnSegundaPagina()
@Test void sinQr()                   // presente false, descripcion "No se encontró código QR de ARCA."
@Test void qrQueNoEsDeArca()         // QR con "hola" → descripcion "...no corresponde a ARCA..."
```

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implementar. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat: lectura y decodificación del QR de ARCA`

---

### Task 4: Procesamiento del documento (detección, texto, rasterización)

**Files:**
- Create: `documento/TipoDocumento.java`, `documento/DetectorTipoDocumento.java`, `documento/DocumentoProcesado.java`, `documento/ProcesadorDocumento.java`, `error/*.java` (todas las excepciones del árbol de archivos)
- Test: `documento/DetectorTipoDocumentoTest.java`, `documento/ProcesadorDocumentoTest.java`

**Interfaces:**
- Consumes: `ExtractorProperties.documento()`.
- Produces:
  - `abstract class ExtraccionException extends RuntimeException { HttpStatus status(); String titulo(); }` y subclases: `DocumentoInvalidoException` (400), `FormatoNoSoportadoException` (415), `DocumentoDemasiadoGrandeException` (413), `ServicioOcupadoException` (503), `LlmNoDisponibleException` (503), `LlmTimeoutException` (504), `RespuestaLlmInvalidaException` (422). **Los mensajes nunca incluyen contenido del documento ni del LLM.**
  - `enum TipoDocumento { PDF, JPEG, PNG }`; `DetectorTipoDocumento.detectar(byte[]): TipoDocumento` por magic bytes (`%PDF-`, `FF D8 FF`, `89 50 4E 47`); otro → `FormatoNoSoportadoException("El documento no es PDF, JPG ni PNG.")`.
  - `record DocumentoProcesado(TipoDocumento tipo, int paginas, ModoProcesamiento modo, String texto, List<BufferedImage> imagenesLlm, List<BufferedImage> imagenesQr, Map<String, Long> tiemposMs)` — `texto` null en modo IMAGEN; `imagenesLlm` vacía en modo TEXTO; `imagenesQr` siempre presente. `tiemposMs` con claves `deteccion`, `extraccionTexto`, `rasterizacion`.
  - `ProcesadorDocumento.procesar(byte[] contenido): DocumentoProcesado`
    - PDF: `Loader.loadPDF(bytes, "", null, null, IOUtils.createMemoryOnlyStreamCache())`; `InvalidPasswordException` o `IOException` → `DocumentoInvalidoException("El PDF está protegido con contraseña.")` / `("El PDF no pudo leerse.")`. Páginas > `maxPaginas` → `DocumentoDemasiadoGrandeException`.
    - Texto por página con `PDFTextStripper` (`setSortByPosition(true)`), páginas separadas por `\n--- Página N ---\n`. Página "utilizable" si sus caracteres `Character.isLetterOrDigit` ≥ `umbralCaracteresPorPagina`. Todas utilizables → `TEXTO`; si no → `IMAGEN`.
    - Imágenes QR a `dpiQr` siempre; imágenes LLM a `dpiImagen` solo en modo IMAGEN.
    - JPEG/PNG: `ImageIO.read`; aplicar orientación EXIF si existe (JPEG; usar metadata de ImageIO, o dejar sin rotar si no hay EXIF legible); 1 página, modo `IMAGEN`; `imagenesLlm` redimensionada para que el lado mayor ≤ `maxLadoPx`; `imagenesQr` = imagen original.

- [ ] **Step 1: Write failing tests** (PDFs construidos en el test con PDFBox: `PDPageContentStream` + `showText`)

```java
@Test void detectaPdfJpegPng()
@Test void rechazaTextoPlano()                       // "hola".getBytes() → FormatoNoSoportadoException
@Test void pdfConTextoVaPorModoTexto()               // modo TEXTO, texto contiene "Factura N° 0003-00012345", imagenesLlm vacía, imagenesQr.size()==1
@Test void pdfSinTextoVaPorModoImagen()              // página solo con imagen embebida → IMAGEN, imagenesLlm.size()==1
@Test void pdfMixtoVaEnteroPorImagen()               // pág 1 con texto, pág 2 solo imagen → IMAGEN, 2 imágenes
@Test void pdfConMasPaginasQueElLimite()             // 11 páginas → DocumentoDemasiadoGrandeException
@Test void pdfCorruptoEs400()                        // bytes "%PDF-1.7 basura" → DocumentoInvalidoException  [Review Focus 1]
@Test void pdfConContrasenaEs400()                   // PDF cifrado con StandardProtectionPolicy → mensaje "protegido con contraseña" [Review Focus 1]
@Test void fotoGrandeSeRedimensiona()                // PNG 6000x4000 → imagenesLlm.get(0) lado mayor == 2000 [Review Focus 2]
@Test void registraTiempos()                         // tiemposMs contiene "deteccion" y "extraccionTexto"
```

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implementar. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat: detección y procesamiento de PDF e imágenes en memoria`

---

### Task 5: Prompt, JSON schema y parseo de la respuesta del LLM

**Files:**
- Create: `llm/PromptLlm.java`, `llm/EsquemaRespuesta.java`, `llm/ConstructorPrompt.java`, `llm/ValorLlm.java`, `llm/RespuestaLlm.java`, `llm/ParserRespuestaLlm.java`
- Test: `llm/ConstructorPromptTest.java`, `llm/EsquemaRespuestaTest.java`, `llm/ParserRespuestaLlmTest.java`

**Interfaces:**
- Consumes: `SolicitudExtraccion`, `DocumentoProcesado`, `QrArcaResultado`, `DatosQrArca.CLAVES`.
- Produces:
  - `record PromptLlm(String sistema, String usuario, List<String> imagenesBase64, ObjectNode esquema)`
  - `EsquemaRespuesta.para(SolicitudExtraccion, boolean hayQr): ObjectNode` — schema:
    - raíz `{type:object, required:[cabecera, detalle], properties:{cabecera, detalle}}`
    - `cabecera`: object, una propiedad por campo, cada una `{type:object, required:[valor,descripcion,clavesQr], properties:{valor:{type:[string,null]}, descripcion:{type:[string,null]}, clavesQr:{type:array, items:{enum: DatosQrArca.CLAVES}}}}`. Si `!hayQr`, sin `clavesQr`.
    - `detalle`: array de objects con una propiedad por campo de detalle `{valor, descripcion}`; si no hay campos de detalle, `{type:array, maxItems:0}`.
  - `ConstructorPrompt.construir(SolicitudExtraccion, DocumentoProcesado, QrArcaResultado): PromptLlm`
    - `sistema` (texto fijo en español) con estas reglas, en esencia: extraer solo lo que figura en el documento; devolver `valor` exactamente como aparece (sin reformatear); si no está, `valor: null` y `descripcion: "Dato no encontrado."`; si hay varias candidatas, elegir la más adecuada y explicar en `descripcion` cuál se usó y por qué; si es inequívoco, `descripcion: null`; las etiquetas son pistas, no coincidencias exactas; cada fila de la tabla de detalle es un elemento de `detalle`; con QR: indicar en `clavesQr` qué claves del QR corresponden al campo (vacío si ninguna).
    - `usuario`: lista de campos (nombre, etiquetas, descripción, tipo) de cabecera y detalle; bloque "Datos verificados del QR de ARCA" con el JSON de `DatosQrArca` si `presente`; en modo TEXTO, el texto entre `<<<DOCUMENTO` y `DOCUMENTO>>>`; en modo IMAGEN, la indicación de que el documento va como imágenes (una por página).
    - `imagenesBase64`: PNG en Base64 de `imagenesLlm` (vacía en TEXTO).
  - `record ValorLlm(String valor, String descripcion, List<String> clavesQr)`
  - `record RespuestaLlm(Map<String, ValorLlm> cabecera, List<Map<String, ValorLlm>> detalle)`
  - `ParserRespuestaLlm.parsear(String json, SolicitudExtraccion): RespuestaLlm` — JSON no parseable → `RespuestaLlmInvalidaException` (mensaje genérico, sin el JSON). Campos pedidos ausentes → `ValorLlm(null, "Dato no encontrado.", List.of())`; campos no pedidos → ignorados; `clavesQr` con claves no válidas → filtradas; `valor` vacío/blank → null + "Dato no encontrado."

- [ ] **Step 1: Write failing tests**

```java
@Test void esquemaTienePropiedadPorCampo()        // cabecera.properties tiene "numeroComprobante","fechaEmision"; required de cada uno incluye "clavesQr" cuando hayQr
@Test void esquemaSinQrNoTieneClavesQr()
@Test void esquemaSinDetalleEsArrayVacio()        // detalle.maxItems == 0
@Test void promptTextoIncluyeDocumentoYNoImagenes()   // usuario contiene "<<<DOCUMENTO" y el texto; imagenesBase64 vacía
@Test void promptImagenIncluyeImagenesYNoTexto()      // imagenesBase64.size()==paginas; usuario no contiene "<<<DOCUMENTO"
@Test void promptIncluyeDatosQrSoloSiPresente()       // contiene "\"nroCmp\":12345" con QR; no contiene "QR de ARCA" sin QR
@Test void promptIncluyeEtiquetasYDescripcion()       // contiene "Comp. Nro" y "incluyendo punto de venta"
@Test void parseaRespuestaCompleta()
@Test void campoOmitidoQuedaNoEncontrado()            // [Review Focus 4]
@Test void campoExtraSeIgnora()                       // [Review Focus 4]
@Test void clavesQrInvalidasSeFiltran()               // ["nroCmp","inventada"] → ["nroCmp"]
@Test void jsonInvalidoLanzaExcepcionSinContenido()   // mensaje no contiene el texto de entrada
```

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implementar. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat: construcción de prompt, schema de salida y parseo de respuesta del LLM`

---

### Task 6: Cliente HTTP de Ollama

**Files:**
- Create: `llm/LlmCliente.java`, `llm/OllamaLlmCliente.java`
- Test: `llm/OllamaLlmClienteTest.java` (WireMock)

**Interfaces:**
- Consumes: `PromptLlm`, `ExtractorProperties.ollama()`, `ExtractorProperties.debug()`.
- Produces:
  - `interface LlmCliente { String completar(PromptLlm prompt); String modelo(); }` — devuelve el string JSON de `message.content`.
  - `OllamaLlmCliente implements LlmCliente` — `POST {url}/api/chat` con cuerpo:
    `{model, stream:false, format:<esquema>, options:{temperature:0, num_ctx:<numCtx>}, messages:[{role:"system",content:sistema},{role:"user",content:usuario,images:[...]}]}` + `think` solo si configurado. `images` se omite si está vacía. Read timeout = `ollama.timeout`.
    - Conexión rechazada / 5xx / 404 de modelo → `LlmNoDisponibleException("El servicio de IA no está disponible.")`
    - Read timeout → `LlmTimeoutException("El modelo excedió el tiempo máximo de respuesta.")`
    - Logs: solo modelo, cantidad de imágenes, duración y `eval_count` de la respuesta. Si `debug.logPrompts` → loguea prompt y respuesta en DEBUG con advertencia.

- [ ] **Step 1: Write failing tests**

```java
@Test void enviaCuerpoEsperado()       // verify POST /api/chat con jsonPath $.model == "qwen3-vl:4b", $.stream == false, $.format.type == "object", $.options.temperature == 0
@Test void omiteImagesSiNoHay()        // jsonPath $.messages[1].images ausente
@Test void devuelveContenido()         // stub {"message":{"content":"{\"cabecera\":{}}"}} → "{\"cabecera\":{}}"
@Test void timeoutEs504()              // stub withFixedDelay(2000), timeout 500ms → LlmTimeoutException
@Test void servidorCaidoEs503()        // puerto sin servidor → LlmNoDisponibleException
@Test void modeloInexistenteEs503()    // stub 404 → LlmNoDisponibleException
```

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implementar con `RestClient` + `JdkClientHttpRequestFactory` (read timeout). **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat: cliente HTTP de Ollama con salida estructurada y manejo de errores`

---

### Task 7: Conciliación con QR y verificación textual

**Files:**
- Create: `conciliacion/ConciliadorQr.java`, `conciliacion/VerificadorTextual.java`
- Test: `conciliacion/ConciliadorQrTest.java`, `conciliacion/VerificadorTextualTest.java`

**Interfaces:**
- Consumes: `ValorLlm`, `CampoSolicitado`, `DatosQrArca`, `Normalizador`, `ResultadoNormalizacion`.
- Produces:
  - `ConciliadorQr.conciliar(CampoSolicitado campo, ValorLlm llm, DatosQrArca qr /* nullable */): ValorCampo` — combina normalización + reglas del spec §7.4:
    1. Sin QR o `clavesQr` vacía → normalizar `llm.valor()`; `fuente = DOCUMENTO` (o null si valor null); `descripcion` = `llm.descripcion()` + observación de normalización (unidas con " ").
    2. Una sola clave **no código** → valor del QR normalizado por `campo.tipo()` (CUIT → string 11 dígitos; FECHA → ISO; IMPORTE/NUMERO → BigDecimal; TEXTO → `toString`), `fuente = QR_ARCA`. Si el valor del documento normalizado existe y difiere → agregar `"En el documento figura <crudo>; el QR indica <qr>; se usó el QR."`; si coincide o es null → descripción del LLM.
    3. Varias claves, o claves solo de código → valor del documento normalizado, `fuente = DOCUMENTO`. Verificación: grupos de dígitos del valor del documento sin ceros a la izquierda vs. valores de las claves (no código) sin ceros a la izquierda, comparados como multiconjunto. Coinciden → agregar `"Verificado con QR de ARCA."`. No coinciden → agregar `"No coincide con el QR de ARCA (<clave>=<valor>, ...); verificar."`. Si solo hay claves código → sin verificación.
  - `VerificadorTextual.aparece(String crudo, Object normalizado, TipoCampo tipo, String texto): boolean`
    - TEXTO: `crudo` (espacios colapsados, sin distinguir mayúsculas) contenido en el texto (espacios colapsados).
    - IMPORTE/NUMERO: algún token numérico del texto (regex `-?\(?\$?\s?\d[\d.,]*`) normalizado con `Normalizador` es `compareTo == 0`.
    - FECHA: algún token de fecha del texto normaliza a la misma ISO.
    - CUIT: algún token `\d{2}-?\d{8}-?\d` con los mismos 11 dígitos.
  - Mensaje cuando no aparece (lo agrega Task 8): `"Valor no encontrado literalmente en el documento; verificar."`

- [ ] **Step 1: Write failing tests** (con `DatosQrArca` del ejemplo del spec)

```java
@Test void sinClavesQrUsaDocumento()                 // "15.000,00" IMPORTE → 15000.00, DOCUMENTO
@Test void unaClaveIgualUsaQrSinDiscrepancia()      // importeTotal "15.000,00", clavesQr [importe] → 15000.00, QR_ARCA, descripcion sin "se usó el QR"
@Test void unaClaveDistintaInformaDiscrepancia()    // "14.000,00" → valor 15000.0, descripcion contiene "En el documento figura 14.000,00; el QR indica 15000"
@Test void cuitDelQrComoString()                     // clavesQr [cuit], tipo CUIT → "30712345671"
@Test void numeroCompuestoVerificado()               // "A 0003-00012345", [ptoVta,nroCmp] → valor "A 0003-00012345", DOCUMENTO, "Verificado con QR de ARCA."
@Test void numeroCompuestoNoCoincide()               // "0003-00012346" → contiene "No coincide con el QR de ARCA"
@Test void soloClaveCodigoSinVerificacion()         // tipoComprobante "Factura A", [tipoCmp] → "Factura A", DOCUMENTO, sin "Verificado"
@Test void valorNullSinQrQuedaNoEncontrado()
// VerificadorTextualTest
@Test void importeConDistintoFormato()               // crudo "15000", texto "TOTAL $ 15.000,00" → true
@Test void fechaConDistintoFormato()                 // ISO 2026-10-02, texto "Fecha: 02/10/26" → true
@Test void textoInventado()                          // "Energía reactiva" en texto sin esa frase → false
@Test void cuitConGuiones()                          // "30712345671", texto "CUIT: 30-71234567-1" → true
```

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implementar. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat: conciliación con QR de ARCA y verificación textual`

---

### Task 8: Servicio orquestador y límite de concurrencia

**Files:**
- Create: `servicio/LimitadorConcurrencia.java`, `servicio/ExtraccionService.java`
- Test: `servicio/LimitadorConcurrenciaTest.java`, `servicio/ExtraccionServiceTest.java` (con `LlmCliente` falso)

**Interfaces:**
- Consumes: todo lo anterior.
- Produces:
  - `LimitadorConcurrencia.ejecutar(Supplier<T> tarea): T` — `Semaphore(maxLlm, fair=true)`, `tryAcquire(esperaMaxima)`; si no → `ServicioOcupadoException("El servicio está procesando otros documentos; reintente más tarde.")`; libera en `finally`.
  - `ExtraccionService.extraer(byte[] documento, SolicitudExtraccion solicitud): ResultadoExtraccion`
    1. `ProcesadorDocumento.procesar` → 2. `LectorQrArca.leer(imagenesQr)` (tiempo `qr`) → 3. `ConstructorPrompt.construir` → 4. `limitador.ejecutar(() -> llm.completar(prompt))` con **1 reintento** si `ParserRespuestaLlm` lanza `RespuestaLlmInvalidaException` (tiempo `llm` acumulado) → 5. por cada campo de cabecera `ConciliadorQr.conciliar`; detalle fila por fila con `qr = null` → 6. si modo TEXTO, para cada valor con `fuente == DOCUMENTO` y valor no null, si `!VerificadorTextual.aparece(...)` agregar el mensaje de Task 7 → 7. `Metadatos` con `verificacionTextual = (modo == TEXTO)`, `modelo = llm.modelo()`, `tiemposMs` en orden `deteccion, qr, extraccionTexto, rasterizacion, llm, total`.
    - Log INFO al final: tipo, páginas, modo, QR presente, cantidad de campos, tiempos. Nada más.

- [ ] **Step 1: Write failing tests**

```java
@Test void limitadorRechazaCuandoEstaOcupado()     // maxLlm 1, espera 100ms, tarea bloqueada en otro hilo → ServicioOcupadoException
@Test void limitadorLiberaTrasExcepcion()
@Test void flujoTextoConQr()          // PDF generado con texto + QR ARCA; LLM falso devuelve JSON fijo → cabecera.importeTotal.fuente QR_ARCA, metadatos.modo TEXTO, tiemposMs keys en orden
@Test void valorInventadoSeMarca()    // LLM devuelve "Energía reactiva" ausente del texto → descripcion contiene "verificar"
@Test void reintentaUnaVezSiJsonInvalido()   // 1ª "no json", 2ª válida → OK, llm llamado 2 veces
@Test void fallaTrasDosJsonInvalidos()       // → RespuestaLlmInvalidaException
@Test void modoImagenSinVerificacion()       // PNG → verificacionTextual false, ninguna descripción con "verificar"
@Test void detalleSeConciliaFilaPorFila()    // 2 filas, IMPORTE normalizado en cada una
```

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implementar. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat: servicio de extracción con límite de concurrencia`

---

### Task 9: Controller REST, validación y manejo de errores

**Files:**
- Create: `api/ExtraccionController.java`, `api/ValidadorSolicitud.java`, `api/ManejadorErrores.java`
- Test: `api/ExtraccionControllerTest.java` (`@WebMvcTest` o `@SpringBootTest` + MockMvc, `LlmCliente` como `@MockitoBean`), `api/LogsSinContenidoTest.java`

**Interfaces:**
- Consumes: `ExtraccionService.extraer`.
- Produces:
  - `POST /api/v1/extracciones/sync` (`consumes = multipart/form-data`): `@RequestPart("documento") MultipartFile`, `@RequestPart("solicitud") SolicitudExtraccion` → `200 ResultadoExtraccion`.
  - `ValidadorSolicitud.validar(SolicitudExtraccion)` → `DocumentoInvalidoException` (400) si: ambas listas vacías (`"La solicitud no contiene campos."`); `nombre` no cumple `^[A-Za-z][A-Za-z0-9_]{0,63}$` (`"El nombre de campo '<nombre>' no es válido: use letras, números y guión bajo, comenzando con letra."`); nombres duplicados dentro de cabecera o dentro de detalle.
  - `ManejadorErrores` (`@RestControllerAdvice`): `ExtraccionException` → `ProblemDetail(status, titulo, mensaje)`; `MaxUploadSizeExceededException` → 413; parte faltante / JSON de solicitud inválido → 400; cualquier otra → 500 `"Error interno."` **sin** el mensaje original. Log de errores: clase de excepción + status, nunca `getMessage()` de excepciones de terceros.

- [ ] **Step 1: Write failing tests**

```java
@Test void extraccionOk()                    // multipart con PDF de texto + solicitud → 200, $.cabecera.numeroComprobante.valor existe, $.metadatos.modoProcesamiento == "TEXTO"
@Test void sinCamposEs400()
@Test void nombreConEspaciosEs400()          // "número factura" → 400, detail contiene "no es válido"   [Review Focus 3]
@Test void nombresDuplicadosEs400()          // [Review Focus 3]
@Test void formatoNoSoportadoEs415()         // detail "El documento no es PDF, JPG ni PNG."
@Test void archivoMayorA10MbEs413()
@Test void ollamaCaidoEs503()                // mock lanza LlmNoDisponibleException → 503, content-type application/problem+json
@Test void timeoutEs504()
@Test void errorInesperadoEs500SinDetalleInterno()   // mock lanza RuntimeException("secreto") → detail "Error interno.", body no contiene "secreto"
// LogsSinContenidoTest (@ExtendWith(OutputCaptureExtension.class)) [Review Focus 5]
@Test void logsNoContienenDatosDelDocumento()   // PDF con texto "CLIENTE-SECRETO-XYZ" y "CUIT 30-71234567-1"; LLM mock devuelve esos valores;
                                                // también caso en que el LLM devuelve JSON inválido 2 veces que contiene "CLIENTE-SECRETO-XYZ";
                                                // assert output no contiene "CLIENTE-SECRETO-XYZ" ni "30712345671"
```

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implementar. **Step 4:** `./scripts/mvn.sh -q test` (suite completa) → PASS.
- [ ] **Step 5: Commit** `feat: endpoint REST de extracción sincrónica con manejo de errores`

---

### Task 10: Docker, Compose y README

**Files:**
- Create: `Dockerfile`, `docker-compose.yml`, `docker-compose.gpu.yml`, `scripts/descargar-modelo.sh`, `README.md`, `ejemplos/solicitud.json`

**Interfaces:**
- Consumes: la app completa; variables `EXTRACTOR_*`.
- Produces:
  - `Dockerfile` multi-stage: build en `maven:3.9-eclipse-temurin-21` (`mvn -q -DskipTests package`), runtime `eclipse-temurin:21-jre`, usuario no root, `EXPOSE 8080`.
  - `docker-compose.yml`:
    - volumen nombrado `ollama-modelos` (`name: ollama-modelos`).
    - red `ia` con `internal: true`; red `publica` normal.
    - `ollama`: `ollama/ollama`, solo red `ia`, sin puertos publicados, env `OLLAMA_KEEP_ALIVE=30m`, `OLLAMA_NUM_PARALLEL=1`.
    - `extractor-api`: build `.`, redes `ia` y `publica`, `ports: 8080:8080`, env `EXTRACTOR_OLLAMA_URL=http://ollama:11434`, `EXTRACTOR_OLLAMA_MODELO=${EXTRACTOR_OLLAMA_MODELO:-qwen3-vl:4b}`, `depends_on: ollama`.
  - `docker-compose.gpu.yml`: override de `ollama` con `deploy.resources.reservations.devices: [{driver: nvidia, count: all, capabilities: [gpu]}]`.
  - `scripts/descargar-modelo.sh <modelo>`: contenedor temporal `ollama/ollama` con el volumen `ollama-modelos` en red con salida a internet, `ollama pull <modelo>`, y lo elimina. Es el **único** paso con acceso externo.
  - `README.md`: requisitos (Docker; NVIDIA Container Toolkit para GPU), descarga del modelo, `docker compose up -d --build`, variante GPU, ejemplo `curl` del spec, tabla de variables de entorno, nota de confidencialidad y de `debug.log-prompts`.

- [ ] **Step 1:** Verificar en ollama.com/library el tag del modelo por defecto (`qwen3-vl:4b`; alternativa `qwen2.5vl:7b`) y su **licencia** (debe ser Apache 2.0). Si el tag no existe o la licencia no es Apache, elegir el tamaño chico con licencia Apache disponible y actualizar el default en `ExtractorProperties`, compose y README. Confirmar si el modelo soporta `think` y, si es así, fijar `EXTRACTOR_OLLAMA_THINK=false` en compose.
- [ ] **Step 2:** Escribir los archivos.
- [ ] **Step 3:** `./scripts/descargar-modelo.sh qwen3-vl:4b` → termina con `success`. Vigilar espacio en disco (`df -h ~`, la notebook tiene ~21 GB libres).
- [ ] **Step 4:** `docker compose up -d --build` → ambos contenedores `running`; `docker compose exec ollama ollama list` muestra el modelo.
- [ ] **Step 5:** Verificar aislamiento: `docker compose exec ollama sh -c 'curl -sS -m 5 https://ollama.com || echo SIN_SALIDA'` → `SIN_SALIDA` (si la imagen no trae curl, usar `ollama pull` de un modelo inexistente y verificar que falla por red).
- [ ] **Step 6:** Smoke test con un PDF de texto armado a mano (o el primero de Task 11 si ya existe): `curl -F documento=@... -F "solicitud=@ejemplos/solicitud.json;type=application/json" localhost:8080/api/v1/extracciones/sync` → 200 con `metadatos.tiemposMs.total`.
- [ ] **Step 7: Commit** `feat: dockerización con Ollama aislado y documentación`

---

### Task 11: Generador de comprobantes sintéticos

**Files:**
- Create: `src/test/java/ar/comprobantes/extractor/sintetico/GeneradorComprobantes.java` (main), `DisenoComprobante.java` (interfaz), `DisenoRetailA.java`, `DisenoEnergiaB.java`, `DisenoNotaCreditoA.java`, `DisenoNotaDebitoB.java`, `DatosComprobante.java`, `Degradador.java`
- Create (salida, versionada): `benchmark/dataset/<id>/{documento.pdf|documento.jpg, solicitud.json, esperado.json}`
- Test: `sintetico/GeneradorComprobantesTest.java`

**Interfaces:**
- Consumes: `DatosQrArca` (para armar el QR), `ValidadorCuit` (CUIT ficticios válidos), `ProcesadorDocumento`/`LectorQrArca` (en el test).
- Produces:
  - `record DatosComprobante(...)`: emisor (razón social ficticia, CUIT), receptor, tipo, punto de venta, número, fechas, líneas (concepto, cantidad, precio unitario, importe), total, `DatosQrArca qr` (nullable), `BigDecimal totalImpreso` (para la discrepancia).
  - `interface DisenoComprobante { String id(); byte[] pdfConTexto(DatosComprobante d); SolicitudExtraccion solicitud(); Map<String,Object> esperado(DatosComprobante d); }` — `esperado` con forma `{cabecera:{nombre:valorNormalizado|null}, detalle:[{...}]}`.
  - Diseños (datos 100% ficticios, etiquetas distintas entre diseños):
    - `retail-a`: Factura A, 8 líneas, etiquetas "Factura N°", "Fecha", "TOTAL", QR válido.
    - `energia-b`: Factura B de servicio eléctrico, conceptos (cargo fijo, energía activa en kWh), "N° de medidor", "1er vto."/"2do vto.", "Comp. Nro", QR válido.
    - `nc-a`: Nota de Crédito A, 3 líneas, **sin QR**.
    - `nd-b`: Nota de Débito B, 2 líneas, QR con importe **distinto** al impreso (esperado: valor del QR).
  - Las solicitudes incluyen al menos un campo que **no existe** en el documento (esperado `null`) para medir invenciones.
  - `Degradador.escaneo(BufferedImage): BufferedImage` (rotación ±1°, ruido gaussiano leve, gris) y `foto(BufferedImage): BufferedImage` (+ perspectiva/brillo leve, JPEG calidad 0.8).
  - `GeneradorComprobantes.main(String[] args)` con `args[0]` = directorio salida: por diseño genera `<diseño>-texto` (PDF), `<diseño>-escaneo` (PDF solo imagen a 200 DPI) y `<diseño>-foto` (JPG) → **12 documentos**.

- [ ] **Step 1: Write failing tests**

```java
@Test void generaDoceDocumentos()          // en @TempDir → 12 subcarpetas, cada una con documento.*, solicitud.json, esperado.json
@Test void cuitsFicticiosSonValidos()
@Test void pdfTextoVaPorModoTextoYEscaneoPorImagen()   // ProcesadorDocumento: retail-a-texto → TEXTO; retail-a-escaneo → IMAGEN
@Test void qrLegibleEnLasTresFormas()      // LectorQrArca encuentra QR en retail-a-{texto,escaneo,foto}; nc-a-* sin QR
@Test void ndbTieneDiscrepancia()          // qr.importe != totalImpreso; esperado.importeTotal == qr.importe
```

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implementar. **Step 4:** Run → PASS.
- [ ] **Step 5:** Generar el dataset: `./scripts/mvn.sh -q test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=ar.comprobantes.extractor.sintetico.GeneradorComprobantes -Dexec.args=benchmark/dataset` → 12 carpetas. Abrir 2–3 documentos y confirmar visualmente que se ven como comprobantes plausibles.
- [ ] **Step 6: Commit** `test: generador de comprobantes sintéticos y dataset`

---

### Task 12: Benchmark de tiempos y precisión + primera medición

**Files:**
- Create: `src/test/java/ar/comprobantes/extractor/benchmark/BenchmarkRunner.java` (main), `benchmark/Comparador.java`
- Test: `benchmark/ComparadorTest.java`
- Create (salida, no versionada): `benchmark/resultados/<timestamp>-<modelo>.csv`

**Interfaces:**
- Consumes: el dataset de Task 11 (mismo formato para `benchmark/reales/`), el servicio corriendo.
- Produces:
  - `Comparador.comparar(Map<String,Object> esperado, JsonNode obtenido): ResultadoComparacion` con `record ResultadoComparacion(int camposTotales, int aciertos, int inventados, int omitidos, List<String> errores)`. Igualdad: números por `compareTo`; strings trim + case-insensitive + espacios colapsados; `inventado` = esperado null y obtenido no null; `omitido` = esperado no null y obtenido null. Detalle comparado por índice de fila; filas faltantes/sobrantes cuentan como omitidos/inventados por cada campo.
  - `BenchmarkRunner.main` — args: `--url` (default `http://localhost:8080`), `--dataset` (default `benchmark/dataset`), `--concurrencia` (default 1), `--salida` (default `benchmark/resultados`). Envía cada documento (`HttpClient`, timeout 5 min), escribe CSV con columnas `documento,forma,modo,qrPresente,status,totalMs,llmMs,aciertos,camposTotales,inventados,omitidos,cumple60s` y una línea de `modelo` en cabecera; imprime resumen por modo: p50/p95 de `totalMs`, % aciertos, total inventados, % de documentos ≤ 60 s.

- [ ] **Step 1: Write failing tests**

```java
@Test void cuentaAciertos()            // esperado {importeTotal:15000} vs obtenido valor 15000.00 → aciertos 1
@Test void detectaInventado()          // esperado null, obtenido "X" → inventados 1
@Test void detectaOmitido()
@Test void comparaDetallePorFila()     // 2 filas esperadas, 1 obtenida → omitidos == campos por fila
@Test void textoIgnoraMayusculasYEspacios()
```

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implementar. **Step 4:** Run → PASS.
- [ ] **Step 5: Primera medición en la notebook:** con `docker compose up -d`, correr `./scripts/mvn.sh -q test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=ar.comprobantes.extractor.benchmark.BenchmarkRunner -Dexec.args="--url http://host.docker.internal:8080"` (en Linux agregar `--add-host=host.docker.internal:host-gateway` en `scripts/mvn.sh` o correr con `--network host`). Esperado: 12 filas en el CSV y el resumen impreso. Repetir con `--concurrencia 2`.
- [ ] **Step 6:** Agregar al README una sección "Cómo medir" y una tabla con los resultados de la notebook (modelo, modo, p50/p95, % aciertos, inventados), indicando que son de CPU sin GPU.
- [ ] **Step 7: Commit** `test: benchmark de tiempos y precisión con resultados iniciales`
