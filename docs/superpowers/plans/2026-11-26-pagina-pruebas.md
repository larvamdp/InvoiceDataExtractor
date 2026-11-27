# Página de pruebas interna — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Página web en `/pruebas`, servida por el propio servicio, para subir una factura, marcar campos y ver el resultado de `POST /api/v1/extracciones/sync`.

**Architecture:** Tres archivos estáticos (HTML, JS, CSS) en `src/main/resources/static/pruebas/`, más un `WebMvcConfigurer` que hace forward de `/pruebas` y `/pruebas/` a `index.html`. El JS llama al endpoint real con `fetch` + `FormData`. No hay cambios en la API.

**Tech Stack:** Spring Boot 3.5 / Java 21 (recursos estáticos, `WebMvcConfigurer`), HTML + CSS + JavaScript sin frameworks (ES2020, módulos no necesarios).

**Spec:** `docs/superpowers/specs/2026-11-26-pagina-pruebas-design.md`

## Global Constraints

- Sin dependencias Maven nuevas, sin Node ni build de frontend, sin CDN, fuentes ni recursos externos. Todo se sirve desde el jar.
- La página queda siempre visible, sin interruptor de configuración.
- No se cambia el contrato de la API.
- La parte `solicitud` se envía como `Blob` con tipo `application/json`. Si no, el servidor responde 415.
- Regla de nombre de campo, idéntica a la del servidor: `^[A-Za-z][A-Za-z0-9_]{0,63}$`, sin duplicados dentro de la sección.
- Tipos de campo: `TEXTO | NUMERO | IMPORTE | FECHA | CUIT`.
- La página no registra ni guarda contenido: sin `console.log` de la respuesta ni del documento, y sin `localStorage` de resultados.
- Los textos de la UI van en castellano rioplatense neutro, como el resto del proyecto.

## Review Focus

1. **Texto malicioso dentro de una factura.** Los valores, descripciones y el JSON vienen del documento vía LLM. Un valor como `<img src=x onerror=alert(1)>` debe verse como texto y nunca ejecutarse. Todo se inserta con `textContent` o `createElement`, nunca con `innerHTML` sobre datos de la respuesta.
2. **Respuesta de error que no es JSON.** Por ejemplo, el 413 de Tomcat, un proxy intermedio o una caída de red. La página muestra el status y un mensaje genérico sin romperse, y los botones vuelven a quedar habilitados.
3. **Espera larga y doble clic.** Mientras hay una solicitud en curso, "Extraer" queda deshabilitado, así que no hay envíos duplicados. "Cancelar" corta la espera y devuelve la UI al estado listo.
4. **Campo propio inválido o repetido.** Si el nombre no cumple la regla, o ya existe en la misma sección (incluidos los del catálogo), se rechaza en el navegador con un mensaje y no se agrega.
5. **Respuesta parcial.** Solo campos de detalle (sin cabecera), `detalle: []` o `qrArca.presente: false`: las pestañas muestran "Sin campos de cabecera" o "Sin filas", sin errores de JS.

No hay framework de tests de JS. Estos cinco puntos se verifican a mano en el Task 2, Step 4, y la revisión de código los mira explícitamente.

---

### Task 1: Ruta `/pruebas` y página base servida

**Files:**
- Create: `src/main/java/ar/comprobantes/extractor/config/PaginaPruebasConfig.java`
- Create: `src/main/resources/static/pruebas/index.html` (esqueleto mínimo, se completa en el Task 2)
- Create: `src/main/resources/static/pruebas/pruebas.js` (vacío con un comentario de cabecera)
- Create: `src/main/resources/static/pruebas/pruebas.css` (vacío con un comentario de cabecera)
- Test: `src/test/java/ar/comprobantes/extractor/api/PaginaPruebasTest.java`

**Interfaces:**
- Produces:
  - `/pruebas` y `/pruebas/` hacen forward a `/pruebas/index.html`.
  - `index.html` referencia `pruebas.css` y `pruebas.js` con rutas relativas a `/pruebas/` (`/pruebas/pruebas.css`, `/pruebas/pruebas.js`). Deben ser rutas absolutas, para que funcionen igual con o sin barra final.

- [ ] **Step 1: Escribir el test que falla**

`@SpringBootTest @AutoConfigureMockMvc`, con `@MockitoBean LlmCliente llm`, como en `ExtraccionControllerTest`, para no depender del proveedor.

```java
@Test void paginaSinBarra() throws Exception {
    mvc.perform(get("/pruebas")).andExpect(status().isOk())
        .andExpect(forwardedUrl("/pruebas/index.html"));
}
@Test void paginaConBarra() throws Exception {
    mvc.perform(get("/pruebas/")).andExpect(status().isOk())
        .andExpect(forwardedUrl("/pruebas/index.html"));
}
@Test void indexSeSirveYReferenciaRecursosLocales() throws Exception {
    mvc.perform(get("/pruebas/index.html")).andExpect(status().isOk())
        .andExpect(content().string(containsString("/pruebas/pruebas.js")))
        .andExpect(content().string(containsString("/pruebas/pruebas.css")))
        .andExpect(content().string(not(containsString("http://"))))
        .andExpect(content().string(not(containsString("https://"))));
}
@Test void scriptSeSirve() throws Exception {
    mvc.perform(get("/pruebas/pruebas.js")).andExpect(status().isOk());
}
@Test void apiSigueIgual() throws Exception {
    // la configuración nueva no captura rutas de la API
    mvc.perform(get("/api/v1/extracciones/sync")).andExpect(status().isMethodNotAllowed());
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./scripts/mvn.sh -q test -Dtest=PaginaPruebasTest`

Expected: FAIL. `/pruebas` devuelve 404 y los recursos no existen.

- [ ] **Step 3: Implementar**

- `PaginaPruebasConfig implements WebMvcConfigurer`, anotada con `@Configuration`.
- `addViewControllers` registra `/pruebas` y `/pruebas/` con `setViewName("forward:/pruebas/index.html")`.
- `index.html`: `<!doctype html>`, `lang="es"`, charset y viewport, `<title>Pruebas de extracción</title>`, el link al CSS y el script con `defer`.

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./scripts/mvn.sh -q test -Dtest=PaginaPruebasTest`

Expected: PASS. Si `apiSigueIgual` devuelve otro status (por ejemplo 400 o un 405 mapeado por `ManejadorErrores`), ajustar la aserción al status que la API ya devolvía en `main` para `GET`; no cambiar la API.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ar/comprobantes/extractor/config/PaginaPruebasConfig.java src/main/resources/static/pruebas src/test/java/ar/comprobantes/extractor/api/PaginaPruebasTest.java
git commit -m "feat: ruta /pruebas para la página de pruebas interna"
```

---

### Task 2: La página (diseño B): campos, extracción y resultado

**Files:**
- Modify: `src/main/resources/static/pruebas/index.html`
- Modify: `src/main/resources/static/pruebas/pruebas.js`
- Modify: `src/main/resources/static/pruebas/pruebas.css`
- Modify (solo si cambia algún texto que el test busca): `src/test/java/ar/comprobantes/extractor/api/PaginaPruebasTest.java`

**Interfaces:**
- Consumes: la ruta y los archivos del Task 1, y el contrato de la API, que es:
  - **Petición:** `multipart/form-data` con `documento` (archivo) y `solicitud` (`application/json`): `{"camposCabecera":[{nombre,etiquetas[],descripcion,tipo}], "camposDetalle":[...]}`.
  - **Respuesta 200:**
    - `cabecera`: objeto `nombre → {valor, descripcion, fuente}`.
    - `detalle`: arreglo de objetos `nombre → {valor, descripcion, fuente}`.
    - `qrArca`: `{presente, datos, descripcion}`.
    - `metadatos`: `{tipoDocumento, paginas, modoProcesamiento, verificacionTextual, modelo, tiemposMs{deteccion, qr, extraccionTexto, rasterizacion, llm, esperaCola, total}}`.
  - **Error:** `application/problem+json` con `{status, title, detail}`, más el header `Retry-After` en algunos 503.
- Produces (nombres en `pruebas.js`, para que la revisión los encuentre):
  - `const CATALOGO = { cabecera: [...], detalle: [...] }`. Cada entrada es `{nombre, etiquetas, descripcion?, tipo, marcado}`.
  - `const NOMBRE_VALIDO = /^[A-Za-z][A-Za-z0-9_]{0,63}$/`.
  - `function armarSolicitud()`, que devuelve `{camposCabecera, camposDetalle}` con solo los marcados.
  - `async function extraer()`.
  - `function mostrarResultado(respuesta)`.
  - `function mostrarError(status, problema, retryAfter)`. `problema` puede ser `null` si la respuesta no era JSON.

- [ ] **Step 1: Catálogo y panel izquierdo**

`CATALOGO`, exactamente con estos nombres y tipos (las etiquetas en castellano quedan a criterio, por ejemplo `cae`: `["CAE", "C.A.E.", "Cód. autorización"]`). Marcados por defecto: los de `ejemplos/solicitud.json`, con las mismas etiquetas y descripción que ese archivo.

- **cabecera:**

  | Campo | Tipo | Marcado |
  |---|---|---|
  | `numeroComprobante` | TEXTO | sí |
  | `fechaEmision` | FECHA | sí |
  | `importeTotal` | IMPORTE | sí |
  | `tipoComprobante` | TEXTO | no |
  | `cuitEmisor` | CUIT | no |
  | `razonSocialEmisor` | TEXTO | no |
  | `cuitReceptor` | CUIT | no |
  | `importeNeto` | IMPORTE | no |
  | `importeIva` | IMPORTE | no |
  | `cae` | TEXTO | no |
  | `fechaVencimientoCae` | FECHA | no |
  | `fechaVencimiento` | FECHA | no |
  | `numeroOrdenCompra` | TEXTO | no |
  | `moneda` | TEXTO | no |

- **detalle:**

  | Campo | Tipo | Marcado |
  |---|---|---|
  | `concepto` | TEXTO | sí |
  | `cantidad` | NUMERO | sí |
  | `precioUnitario` | IMPORTE | sí |
  | `importe` | IMPORTE | no |
  | `alicuotaIva` | NUMERO | no |

Panel izquierdo:
- **Zona de documento:** arrastrar y soltar más `<input type=file accept=".pdf,.jpg,.jpeg,.png">`. Muestra el nombre y el tamaño del archivo elegido.
- **Listas "Cabecera" y "Detalle":** casillas con nombre y tipo, más "Marcar todos" y "Ninguno" por sección.
- **"+ campo propio":** formulario inline con nombre, sección, etiquetas (separadas por coma, se recortan los espacios y se descartan las vacías), descripción y tipo (`<select>`). Valida con `NOMBRE_VALIDO` y rechaza los duplicados en la sección (Review Focus 4). El campo agregado entra marcado y se puede quitar.
- **Botones:**
  - "Extraer": deshabilitado sin documento o sin ningún campo marcado.
  - "Cancelar": visible solo durante una solicitud.
  - "Copiar solicitud JSON": usa `navigator.clipboard.writeText(JSON.stringify(armarSolicitud(), null, 2))`. Si el portapapeles no está disponible, por ejemplo en HTTP no-localhost, muestra el JSON en un `<textarea>` seleccionado para copiarlo a mano.

- [ ] **Step 2: Extracción**

`extraer()`:
- Arma el `FormData`: `documento` = archivo; `solicitud` = `new Blob([JSON.stringify(armarSolicitud())], {type: "application/json"})`.
- Hace `fetch("/api/v1/extracciones/sync", {method: "POST", body, signal})` con un `AbortController`.
- Mientras espera:
  - "Extraer" queda deshabilitado (Review Focus 3).
  - Se muestra un contador de segundos transcurridos.
  - El resultado anterior sigue visible, atenuado.
- **Respuesta OK:** `mostrarResultado(await r.json())`.
- **Respuesta no OK:**
  - Si `content-type` incluye `json`, intenta leer el cuerpo como problema; si no es JSON o falla el parseo, `problema = null` (Review Focus 2).
  - Llama a `mostrarError(r.status, problema, r.headers.get("Retry-After"))`.
- **Cancelación (`AbortError`):** muestra "Cancelado (el servidor puede seguir procesando la solicitud)."
- **Error de red:** `mostrarError(0, null, null)`, con el texto "No se pudo conectar con el servicio".
- En `finally`, la UI vuelve al estado listo.

- [ ] **Step 3: Panel derecho**

Pestañas Cabecera, Detalle, JSON y Tiempos:
- Línea de resumen arriba: status, tiempo total, modo, modelo, páginas y QR sí/no.
- **Cabecera:** tabla Campo / Valor / Fuente / Descripción.
  - Fuente como insignia: `QR_ARCA` → "QR", `DOCUMENTO` → "DOC", `null` → "–".
  - `valor === null` se resalta como no encontrado.
  - Si no hubo campos de cabecera: "Sin campos de cabecera" (Review Focus 5).
- **Detalle:** una fila por ítem y una columna por campo. Cada celda muestra el valor; la descripción va como `title` (tooltip) y se marca si no es `null`. Con `detalle` vacío: "Sin filas".
- **JSON:** `<pre>` con `JSON.stringify(respuesta, null, 2)` como `textContent`.
- **Tiempos:** tabla de `metadatos.tiemposMs` con los nombres del README (`deteccion`, `qr`, `extraccionTexto`, `rasterizacion`, `llm`, `esperaCola`, `total`), más `verificacionTextual` y `qrArca.descripcion`.
- **Error:** bloque destacado con `status`, `title`, `detail` y "Reintentar en N s" si hay `Retry-After`. Con `problema === null`: "Error HTTP {status}" (o el texto de red).
- **Todo el contenido que viene de la respuesta se inserta con `textContent` o `createElement`, nunca con `innerHTML` (Review Focus 1).**

CSS:
- Dos columnas con `grid`; se apilan por debajo de 900 px.
- Variables de color en `:root`, con un bloque `@media (prefers-color-scheme: dark)`.
- Sin fuentes externas (`font-family: system-ui, sans-serif`).

- [ ] **Step 4: Verificación manual (el test de Java no cubre el JS)**

Levantar el servicio desde la rama. Con Ollama: `COMPOSE_PROFILES=ollama docker compose up -d --build`. También vale cualquier proveedor configurado. Abrir `http://localhost:8080/pruebas` y comprobar:

1. Extraer `benchmark/dataset/nc-a-texto/documento.pdf` con los campos por defecto: llegan las cuatro pestañas con datos.
2. Review Focus 1: en la consola del navegador ejecutar `mostrarResultado({cabecera:{x:{valor:"<img src=x onerror=alert(1)>",descripcion:null,fuente:"DOCUMENTO"}},detalle:[],qrArca:{presente:false},metadatos:{tiemposMs:{}}})`. Se ve el texto literal y no aparece ningún alert.
3. Review Focus 2: `mostrarError(502, null, null)` muestra "Error HTTP 502". Subir un archivo `.txt` renombrado a `.pdf` muestra el problema 415 o 400 del servidor.
4. Review Focus 3: con una extracción en curso, "Extraer" está deshabilitado. "Cancelar" vuelve al estado listo.
5. Review Focus 4: el campo propio `1malo` se rechaza, y también `cae` en cabecera (ya existe). `miCampo` se agrega y viaja en la solicitud (verificar con "Copiar solicitud JSON").
6. Review Focus 5: desmarcar toda la cabecera y extraer solo detalle: la pestaña Cabecera dice "Sin campos de cabecera".
7. Ventana angosta: los paneles se apilan. El modo oscuro del sistema se respeta.
8. DevTools → Network: no hay pedidos a otros orígenes.

Run también: `./scripts/mvn.sh -q test -Dtest=PaginaPruebasTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/pruebas
git commit -m "feat: página de pruebas con selección de campos y resultado en pestañas"
```

---

### Task 3: README y suite completa

**Files:**
- Modify: `README.md`. Nueva sección `## Página de pruebas`, después de `## Uso`.

- [ ] **Step 1: Escribir la sección**

Contenido:
- URL `http://localhost:8080/pruebas`.
- Para qué sirve: pruebas internas con el proveedor configurado.
- Qué permite: marcar campos, agregar campos propios, ver cabecera, detalle, JSON y tiempos, y copiar la solicitud JSON para `curl`.
- **Advertencia:**
  - La página está siempre visible.
  - Cualquiera que llegue al puerto puede enviar documentos al proveedor de IA configurado (con Azure o Vertex, el contenido sale hacia el proveedor y consume cuota).
  - Si el puerto es accesible desde la red, restringirlo con `EXTRACTOR_PUERTO=127.0.0.1:8080` o con un firewall.
- "Cancelar" solo corta la espera en el navegador.

- [ ] **Step 2: Suite completa**

Run: `./scripts/mvn.sh -q test`

Expected: BUILD SUCCESS, sin fallos (los logs ERROR de los tests de rutas 500 son esperados).

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: README describe la página de pruebas y su exposición"
```
