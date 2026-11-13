# Invoice Data Extractor — Diseño (Iteración 1)

Fecha: 2026-11-13
Fuente: `especificacion.md` + conversación de diseño.

## 1. Intención y por qué

Servicio web REST que lee comprobantes legales argentinos (Facturas, Notas de Débito, Notas de Crédito) en PDF o imagen y extrae los campos de cabecera y de detalle que el cliente pida, para **facilitar la carga manual** de esos datos en distintos sistemas.

- **Genérico** = los comprobantes vienen de emisores y rubros muy distintos (energía, retail, servicios, etc.) con formatos heterogéneos. El servicio no tiene lógica de negocio: el que llama define qué campos quiere y con qué etiquetas pueden aparecer.
- **Solo Argentina**: se puede aprovechar el QR de ARCA (ex AFIP) de comprobantes electrónicos.
- **Confidencialidad** es la restricción dominante: la IA corre localmente, sin enviar documentos a servicios externos.

## 2. Objetivos

1. Endpoint REST sincrónico que recibe un documento + lista de campos (con etiquetas alternativas) y devuelve JSON con `valor` + `descripcion` por campo.
2. Soportar PDF (con capa de texto o escaneado) e imágenes (JPG/PNG).
3. Extraer campos de cabecera y líneas de detalle.
4. Usar el QR de ARCA, cuando exista, como fuente verificada para los datos que contiene.
5. IA 100% local (Ollama + modelo de pesos abiertos), dockerizado, ejecutable en una notebook Ubuntu de desarrollo (CPU) y en un servidor propio (GPU opcional).
6. **Medir rendimiento** (tiempos por etapa) para decidir el dimensionamiento del servidor. Este es el propósito principal de la iteración 1.

## 3. No-objetivos (iteración 1)

- Procesamiento asincrónico, colas, webhooks, polling (ver §10 Evolución).
- Persistencia de documentos o resultados.
- Autenticación/autorización, multi-tenant, cuotas por cliente.
- Validación online del CAE contra los servicios web de ARCA.
- UI.
- Proveedores de IA en la nube (el diseño los permite vía LangChain4j, pero no se implementan).

## 4. Anti-objetivos (sería un fracaso aunque "funcione")

- Que cualquier dato del documento salga de la infraestructura propia (llamadas a APIs externas, telemetría, logs con contenido del documento).
- Que el servicio **invente** valores: si un dato no está, debe devolver `null` y decirlo en `descripcion`, nunca un valor plausible.
- Que se cuele lógica de un rubro o cliente específico en el núcleo (p. ej. reglas "si es factura de energía...").
- Que el documento o los datos extraídos queden persistidos en disco (archivos temporales incluidos) o en logs.

## 5. Restricciones

- Stack: Java 21, Spring Boot 3.x, Maven. Open source.
- Volumen: ≤100 documentos/mes por cliente; **5 clientes previstos** → ~500 documentos/mes (~25 por día hábil). Concurrencia esperada muy baja (picos de 2–3 simultáneos).
- **Objetivo de rendimiento: ≤ 60 s por documento** (el que envía espera la respuesta). Timeout por defecto de llamada al LLM: 90 s; espera máxima en la cola interna: 60 s (ambos configurables). El objetivo aplica al servidor; en la notebook sin GPU se mide igual, sabiendo que el modo imagen probablemente lo exceda.
- Notebook de desarrollo (verificada): Ubuntu, Intel Comet Lake-U de 8 hilos, 22 GB de RAM (~11 GB disponibles), **sin GPU dedicada**, ~21 GB libres en disco. Entra un modelo 7–8B cuantizado (~6 GB), pero en CPU será lento, sobre todo en modo imagen. El espacio en disco es justo para imágenes Docker + 1–2 modelos.
- Licencias: verificar por tamaño (p. ej. Qwen2.5-VL 3B no es Apache 2.0; 7B sí). En notebook preferir un tamaño chico con licencia Apache (p. ej. Qwen3-VL 4B, a confirmar).
- Servidor: propio, a dimensionar con los resultados de esta iteración.
- Licencias de modelos: preferir Apache 2.0 / MIT. Verificar la licencia del tamaño concreto elegido.

## 6. Enfoques considerados

| Enfoque | Descripción | Trade-off | Decisión |
|---|---|---|---|
| A. Todo como imagen | Rasterizar cada página y enviarla a un modelo de visión. | Un único camino, simple. Lento en CPU; el modelo "lee" números que el PDF ya tenía como texto exacto. | Descartado |
| **B. Híbrido texto/imagen** | Si el PDF tiene capa de texto utilizable → extraer texto con layout (PDFBox) y enviarlo al modelo como texto. Si no (escaneo/imagen) → rasterizar y enviar imágenes al modelo de visión. Mismo modelo para ambos. | Mucho más rápido y exacto en el caso común (comprobantes electrónicos). Dos caminos para probar; requiere heurística de "texto utilizable". | **Elegido** por el usuario |
| C. OCR (Tesseract) + LLM de texto | OCR clásico y luego LLM. | Pierde estructura de tablas de detalle. | Descartado |
| D. Docling (IBM) | Conversión de documentos a Markdown estructurado. | Excelente, pero Python → otro servicio. | Descartado para iteración 1 |

**Adicional elegido por el usuario:** decodificación del **QR de ARCA** en todos los caminos.

## 7. Diseño

### 7.1 Arquitectura

```
docker-compose
├── extractor-api   (Spring Boot, Java 21)
│     POST /api/v1/extracciones/sync
│        │
│        ├─ 1. Detección de tipo (PDF / imagen) por contenido, no por extensión
│        ├─ 2. QR ARCA: rasterizar páginas → ZXing → decodificar URL → JSON base64
│        ├─ 3. Texto: PDFBox con layout (si PDF); evaluar si es "utilizable"
│        ├─ 4. Armado de prompt (texto o imágenes) + campos + datos QR
│        ├─ 5. Llamada al LLM (LangChain4j → Ollama), salida JSON estructurada
│        └─ 6. Post-proceso: validar JSON, conciliar con QR, armar respuesta + métricas
│
└── ollama          (imagen oficial ollama/ollama, volumen para modelos)
      modelo configurable (p. ej. Qwen2.5-VL / Qwen3-VL, 3–4B en notebook, 7–8B en servidor)
```

Internamente, un **semáforo/pool acotado** limita las llamadas concurrentes al LLM (configurable, default 1), para que el diseño ya contemple que el modelo es un recurso escaso. Requests que esperan más del timeout configurado reciben 503.

### 7.2 Contrato de la API

**Request** — `POST /api/v1/extracciones/sync`, `multipart/form-data`:

- `documento`: archivo (PDF, JPG, PNG). Tamaño máx. configurable (default 10 MB). Páginas máx. configurable (default 10).
- `solicitud`: JSON:

```json
{
  "camposCabecera": [
    {
      "nombre": "numeroComprobante",
      "etiquetas": ["Número factura", "Número de factura", "Nro. factura", "Factura N°", "Comp. Nro"],
      "descripcion": "Número completo del comprobante, incluyendo punto de venta",
      "tipo": "TEXTO"
    },
    { "nombre": "fechaEmision", "etiquetas": ["Fecha", "Fecha de emisión"], "tipo": "FECHA" },
    { "nombre": "importeTotal", "etiquetas": ["Total", "Importe total"], "tipo": "IMPORTE" }
  ],
  "camposDetalle": [
    { "nombre": "descripcion", "etiquetas": ["Descripción", "Detalle", "Concepto"], "tipo": "TEXTO" },
    { "nombre": "cantidad", "etiquetas": ["Cant.", "Cantidad"], "tipo": "NUMERO" },
    { "nombre": "precioUnitario", "etiquetas": ["P. Unit.", "Precio unitario"], "tipo": "IMPORTE" }
  ]
}
```

- `nombre` (obligatorio): clave en la respuesta.
- `etiquetas` (opcional, lista): cómo puede aparecer en el documento. Son **pistas**, no coincidencia exacta: el modelo puede reconocer variantes.
- `descripcion` (opcional): aclaración semántica libre para el modelo.
- `tipo` (opcional, default `TEXTO`): `TEXTO | NUMERO | IMPORTE | FECHA | CUIT`. Define normalización de salida:
  - `FECHA` → `yyyy-MM-dd`
  - `IMPORTE`/`NUMERO` → número decimal con punto (convierte `1.234,56` → `1234.56`)
  - `CUIT` → 11 dígitos sin guiones + validación de dígito verificador (si falla, se informa en `descripcion`, se devuelve igual)
- `camposDetalle` opcional; si se omite no se extraen líneas.

**Response 200:**

```json
{
  "cabecera": {
    "numeroComprobante": { "valor": "00003-00012345", "descripcion": "Encontrado como 'Comp. Nro'. Coincide con QR de ARCA.", "fuente": "QR_ARCA" },
    "fechaEmision":      { "valor": "2026-10-02", "descripcion": "Había 'Fecha' y 'Fecha de vencimiento'; se usó 'Fecha' (emisión).", "fuente": "DOCUMENTO" },
    "importeTotal":      { "valor": null, "descripcion": "Dato no encontrado.", "fuente": null }
  },
  "detalle": [
    {
      "descripcion":    { "valor": "Energía activa", "descripcion": null, "fuente": "DOCUMENTO" },
      "cantidad":       { "valor": 350, "descripcion": null, "fuente": "DOCUMENTO" },
      "precioUnitario": { "valor": 123.45, "descripcion": null, "fuente": "DOCUMENTO" }
    }
  ],
  "qrArca": {
    "presente": true,
    "datos": { "ver": 1, "fecha": "2026-10-02", "cuit": 30712345678, "ptoVta": 3, "tipoCmp": 1, "nroCmp": 12345, "importe": 15000.0, "moneda": "PES", "ctz": 1, "tipoDocRec": 80, "nroDocRec": 20123456789, "tipoCodAut": "E", "codAut": 76543210987654 },
    "descripcion": null
  },
  "metadatos": {
    "tipoDocumento": "PDF",
    "paginas": 1,
    "modoProcesamiento": "TEXTO",
    "modelo": "qwen2.5vl:7b",
    "tiemposMs": { "deteccion": 5, "qr": 180, "extraccionTexto": 40, "llm": 8200, "total": 8450 }
  }
}
```

- `fuente`: `QR_ARCA | DOCUMENTO | null`.
- `qrArca.datos`: los datos crudos del QR (también útiles para el sistema que consume aunque no los haya pedido como campo).
- `metadatos.tiemposMs`: requisito central de esta iteración (medir rendimiento).

**Errores:** 400 (archivo inválido, JSON inválido, sin campos), 413 (tamaño/páginas excedidas), 415 (formato no soportado), 503 (modelo ocupado más allá del timeout de cola, u Ollama no disponible), 504 (el LLM excedió timeout), 422 (el LLM devolvió JSON inválido tras reintentos). Cuerpo de error en formato RFC 7807 (`ProblemDetail` de Spring).

### 7.3 Camino híbrido (detalle)

1. **Detección**: por magic bytes (Apache Tika o chequeo manual de cabecera).
2. **PDF con texto**: `PDFTextStripper` con `setSortByPosition(true)` (o `PDFLayoutTextStripper` si la tabla de detalle se desarma). El texto es "utilizable" si supera un umbral de caracteres alfanuméricos por página (configurable). Si alguna página no lo es → modo `IMAGEN` para todo el documento (simplicidad).
3. **PDF sin texto / imagen**: rasterizar con PDFBox a DPI configurable (default 150–200) y enviar páginas como imágenes. Para imágenes de entrada, se envían directamente (redimensionadas si exceden un máximo).
4. **Prompt**: instrucciones en español; para cada campo, nombre + etiquetas + descripción + tipo. Reglas explícitas: no inventar; si no está, `null` y explicarlo; si hay varias candidatas, elegir y explicar cuál y por qué. Se piden los valores tal como figuran y la normalización por `tipo` la hace Java (más confiable que el modelo).
5. **Salida estructurada**: usar el modo JSON / JSON schema de Ollama (`format`) para forzar la forma de la respuesta. Reintento 1 vez si no parsea.

### 7.4 QR de ARCA

- Rasterizar cada página (reutilizando la rasterización si ya se hizo) y buscar QR con **ZXing** (con `TRY_HARDER`).
- La URL tiene la forma `https://www.afip.gob.ar/fe/qr/?p=<base64>` (aceptar también variantes de dominio `arca.gob.ar`). Decodificar el base64 → JSON con campos `ver, fecha, cuit, ptoVta, tipoCmp, nroCmp, importe, moneda, ctz, tipoDocRec, nroDocRec, tipoCodAut, codAut`.
- Si no hay QR o no decodifica: `qrArca.presente=false` con `descripcion` explicativa. Nunca es un error del request.
- **Conciliación con campos pedidos**: los datos del QR se pasan al LLM como "datos verificados" junto con el documento, y el LLM indica, por campo, si corresponde a un dato del QR (mapeo semántico, ya que los nombres de campos son libres). En post-proceso, si un campo fue mapeado a un dato del QR:
  - se usa el valor del QR (`fuente: QR_ARCA`);
  - si el valor leído del documento difiere, se informa en `descripcion` ("En el documento figura X; el QR indica Y; se usó el QR").
- No se consulta ARCA online (no-objetivo).
- **Reglas de mapeo (decisión propia):**
  - Un campo pedido puede mapearse a **una o varias** claves del QR (p. ej. `numeroComprobante` → `ptoVta` + `nroCmp`). El LLM devuelve la lista de claves mapeadas.
  - **Mapeo a una sola clave** (fecha, importe, CUIT): Java toma el valor del QR, lo normaliza según `tipo` y lo devuelve con `fuente: QR_ARCA`.
  - **Mapeo a varias claves** (valores compuestos): se devuelve el valor **tal como figura en el documento** y Java lo verifica contra el QR comparando solo los dígitos significativos (sin ceros a la izquierda, prefijos de letra ni separadores). Si coincide: `fuente: DOCUMENTO` y `descripcion` "Verificado con QR de ARCA". Si no coincide: se informa la discrepancia en `descripcion` y se mantiene el valor del documento.
  - Diferencias solo de presentación (padding, prefijo "A ", guiones) **no** se reportan como discrepancia.
  - Los códigos (`tipoCmp`, `moneda`, `tipoDocRec`) no se traducen en el núcleo: van crudos en `qrArca.datos`. Si un campo pedido se mapea a un código, se devuelve el valor del documento y se verifica igual que los compuestos cuando sea posible.

### 7.4.1 Verificación anti-invención (decisión propia)

- **Modo TEXTO:** después de normalizar, Java verifica que cada valor devuelto (o su forma normalizada: dígitos para números/CUIT, fecha en formatos comunes) aparezca en el texto extraído. Si no aparece, **no se anula**: se conserva el valor y se agrega en `descripcion` "Valor no encontrado literalmente en el documento; verificar". Los valores con `fuente: QR_ARCA` quedan exentos.
- **Modo IMAGEN:** no hay verificación determinística; se agrega en `metadatos` un indicador `verificacionTextual: false`.

### 7.5 Confidencialidad

- Procesamiento en memoria; sin archivos temporales en disco (PDFBox configurado con `MemoryUsageSetting.setupMainMemoryOnly()` o equivalente acotado).
- Logs: nunca contenido del documento, del prompt ni de la respuesta del LLM. Solo metadatos (tamaño, páginas, modo, tiempos, errores). Un flag de debug para loguear prompts existe pero está **desactivado por defecto** y documentado como solo para desarrollo con documentos de prueba.
- Ollama sin acceso a internet en ejecución (la red del compose puede ser `internal` una vez descargados los modelos; la descarga del modelo es un paso separado documentado).
- Sin telemetría externa.

### 7.6 Docker y entornos

- `docker-compose.yml` con `extractor-api` y `ollama`.
- Perfil/override para GPU NVIDIA (`docker-compose.gpu.yml` con `deploy.resources.reservations.devices`), requiere NVIDIA Container Toolkit en el host.
- Notebook Ubuntu: compose base (CPU), modelo chico por variable de entorno.
- Script/instrucción para descargar el modelo (`ollama pull <modelo>`) la primera vez.
- Configuración por variables de entorno: modelo, URL de Ollama, timeouts, concurrencia, límites de tamaño/páginas, DPI, umbral de texto.

### 7.7 Pruebas y medición

- Tests unitarios: normalización por tipo, validación CUIT, decodificación QR (con QR de ejemplo generados), detección de texto utilizable, armado de prompt, parseo/conciliación de la respuesta del LLM (con LLM simulado).
- Tests de integración con Testcontainers + Ollama son opcionales (pesados); como mínimo, un test de integración con el LLM mockeado a nivel HTTP.
- **Set de prueba sintético** (aún no hay documentos reales): un generador (Java, en el módulo de pruebas) que produce comprobantes ficticios con datos conocidos:
  - 3–4 diseños distintos (p. ej. factura retail con muchas líneas, factura de servicio/energía con conceptos, nota de crédito, nota de débito), con variantes de etiquetas ("Nro. factura", "Comp. Nro", "Factura N°"...).
  - Cada uno en tres formas: **PDF con texto**, **PDF escaneado** (página rasterizada, leve rotación/ruido) e **imagen JPG**.
  - QR de ARCA válido en la mayoría; algunos sin QR y alguno con discrepancia deliberada QR vs. documento.
  - Por cada documento, un JSON `esperado` (valores correctos) y la `solicitud` de campos usada.
  - Datos 100% ficticios (CUIT con dígito verificador válido pero inventados).
- **Script de benchmark**: envía el set al servicio y produce un CSV + resumen con, por documento y por modo (TEXTO/IMAGEN): tiempos por etapa, total, aciertos por campo vs. `esperado`, valores inventados (devueltos cuando el esperado es `null`) y cumplimiento del objetivo de 60 s. Soporta ejecutar con N requests concurrentes (default 1; probar 2–3) para dimensionar. Permite comparar modelos cambiando solo la configuración.
- **Documentos reales**: cuando estén disponibles, se suman en una carpeta local fuera del repo (`.gitignore`) con el mismo formato (`solicitud` + `esperado`). Revisión futura acordada con el usuario.

## 8. Decisiones tomadas por el usuario

- Iteración 1: sincrónica, sin persistencia.
- Enfoque B (híbrido texto/imagen).
- Incluir QR de ARCA.
- IA local, dockerizada, probada en notebook Ubuntu y luego en servidor propio.
- "Genérico" = formatos y rubros variados, solo Argentina.

## 9. Decisiones propias (corregibles)

- Java 21 + Spring Boot 3 + Maven + LangChain4j (cliente Ollama) + PDFBox 3 + ZXing.
- Ollama como servidor de modelos; familia Qwen-VL como modelo por defecto (tamaño según entorno). Modelo intercambiable por configuración.
- Contrato `camposCabecera` / `camposDetalle` con `etiquetas`, `descripcion`, `tipo` opcionales.
- Campo `fuente` adicional en la respuesta y bloque `qrArca` con los datos crudos.
- Normalización de tipos en Java, no en el modelo.
- Concurrencia al LLM acotada (default 1) con semáforo interno.
- Si alguna página no tiene texto utilizable, todo el documento va por modo imagen.
- Límites por defecto: 10 MB, 10 páginas.

## 10. Evolución prevista (fuera de alcance, el diseño no debe impedirla)

- Endpoint asincrónico: `POST /api/v1/extracciones` → 202 + id; `GET /api/v1/extracciones/{id}`; webhook opcional. Implica decidir política de almacenamiento temporal (cifrado, TTL).
- Autenticación (API key / OAuth2) y límites por cliente.
- Validación del CAE contra ARCA.
- Proveedor de IA en la nube como alternativa configurable.

## 11. Queda a criterio del implementador

- Estructura de paquetes y clases.
- Elección exacta del extractor de texto con layout (PDFTextStripper vs. alternativas) según resultados con documentos reales.
- Prompt exacto y JSON schema enviado a Ollama.
- Umbrales por defecto (texto utilizable, DPI) ajustados empíricamente.
- Versión exacta del modelo (verificando licencia) y de las librerías.
