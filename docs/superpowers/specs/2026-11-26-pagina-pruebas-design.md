# Página de pruebas interna — diseño

Fecha: 2026-11-26 · Rama: `feat/pagina-pruebas` · Tamaño: cambio chico (diseño corto).

## Intención

Probar el servicio a mano, de forma interna: subir una factura, elegir qué campos buscar y ver qué devuelve con el proveedor de IA configurado. Reemplaza la idea inicial de documentar la API con Swagger, que se descartó por innecesaria.

## Objetivos

- Página web servida por el mismo servicio, siempre visible, en `/pruebas`.
- Diseño de dos paneles (opción B elegida en los bocetos):
  - **Izquierda:**
    - Documento: PDF, JPG o PNG, que se arrastra o se elige.
    - Campos de cabecera y de detalle con casillas.
    - "+ campo propio".
    - Botón "Extraer".
  - **Derecha:** pestañas "Cabecera", "Detalle", "JSON" y "Tiempos".
- Usa el endpoint real `POST /api/v1/extracciones/sync` (multipart: `documento` + `solicitud` como `application/json`), de modo que prueba exactamente lo mismo que un integrador.

## No objetivos

- Swagger/OpenAPI.
- Autenticación de la página o de la API.
- Guardar en el servidor los campos propios o los resultados.
- Vista previa del documento (eso era la opción C).
- Cambiar el contrato de la API.

## Anti-objetivos

- Que la página necesite internet: sin CDN, fuentes ni librerías externas, porque los servidores pueden no tener salida.
- Agregar un build de frontend (Node) o dependencias Maven nuevas.
- Que la página registre o guarde el contenido de los documentos. Se mantiene la regla de confidencialidad del servicio.

## Enfoques considerados

1. **HTML + JavaScript sin frameworks como recursos estáticos de Spring Boot (elegido).**
   - No suma dependencias ni build y funciona sin internet.
   - Llama a la API real.
2. **Thymeleaf.** Descartado:
   - Suma una dependencia y un controlador que llama al servicio por un camino distinto del de la API.
   - Cada extracción recarga la página y se pierde la selección.
3. **React/Vue con build en Maven.** Descartado: es excesivo y suma Node al build.

## Decisiones del usuario

- Diseño B (dos paneles).
- Dentro del mismo servicio y **siempre visible**, sin interruptor.
- Sin Swagger.
- Implementación con HTML + JS sin frameworks.

## Diseño

**Ubicación:**
- Archivos estáticos en `src/main/resources/static/pruebas/`: `index.html`, `pruebas.js` y `pruebas.css`.
- `/pruebas` y `/pruebas/` abren la página. Hace falta mapear esas rutas a `index.html`, porque Spring Boot solo resuelve automáticamente la página de bienvenida de la raíz.

**Catálogo de campos (propuesta, editable por el builder):** vive en `pruebas.js`. Arrancan marcados los de `ejemplos/solicitud.json`.

- **Cabecera:**

  | Campo | Tipo | Marcado por defecto |
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

- **Detalle:**

  | Campo | Tipo | Marcado por defecto |
  |---|---|---|
  | `concepto` | TEXTO | sí |
  | `cantidad` | NUMERO | sí |
  | `precioUnitario` | IMPORTE | sí |
  | `importe` | IMPORTE | no |
  | `alicuotaIva` | NUMERO | no |

- Cada campo lleva etiquetas en castellano, las habituales en facturas argentinas. Los nombres coinciden con los del dataset de benchmark donde existen.

**Campo propio:**
- Formulario con nombre, sección (cabecera o detalle), etiquetas separadas por coma, descripción y tipo (`TEXTO | NUMERO | IMPORTE | FECHA | CUIT`).
- El nombre se valida en el navegador con la misma regla del servidor: `^[A-Za-z][A-Za-z0-9_]{0,63}$`, sin duplicados en la sección.
- Dura mientras la página está abierta.

**Extraer:**
- Arma el `FormData`. La `solicitud` va como `Blob` con tipo `application/json`; si no, el servidor responde 415.
- Muestra el tiempo transcurrido mientras espera, porque con Ollama en CPU puede tardar minutos.
- Botón "Cancelar" (`AbortController`). Cancelar solo deja de esperar del lado del navegador: el servidor termina la llamada al modelo igual.
- Sin documento o sin ningún campo marcado, el botón queda deshabilitado.

**Resultado:**
- **Cabecera:** tabla campo / valor / fuente (`QR_ARCA` o `DOCUMENTO`) / descripción. `valor: null` se resalta como no encontrado, y las descripciones de advertencia ("verificar") quedan visibles.
- **Detalle:** una tabla con una fila por ítem y una columna por campo.
- **JSON:** la respuesta completa, con formato.
- **Tiempos:** `metadatos.tiemposMs`, más modo, modelo, páginas, si hubo QR y verificación textual.
- **Error:** de la respuesta RFC 7807 se muestran `status`, `title` y `detail`, y `Retry-After` si viene.
- El resultado anterior queda visible hasta que llega el nuevo.

**Copiar solicitud JSON:** copia al portapapeles la solicitud armada con los campos marcados, para reutilizarla en `curl` o en una integración.

**Estilo:**
- Sobrio, en el CSS propio de la página.
- Respeta el modo claro y oscuro del sistema.
- En pantallas angostas, los paneles se apilan.

## Pruebas

- Test de Spring MVC: `GET /pruebas` y `GET /pruebas/` devuelven 200 con el HTML, y `pruebas.js` se sirve. Así, un cambio de configuración que rompa la ruta se detecta.
- Verificación manual con el servicio en marcha: correr un documento del dataset con un mock o con Ollama, un error 400 (campo propio inválido forzado) y la cancelación.
- No se agregan tests de JavaScript, porque no hay Node en el build.

## Documentación

Sección "Página de pruebas" en el README:
- URL de la página.
- Para qué sirve.
- Advertencia: está siempre visible y cualquiera que llegue al puerto puede enviar documentos al proveedor de IA configurado. Si el puerto es accesible desde la red, restringirlo con `EXTRACTOR_PUERTO=127.0.0.1:8080` o con un firewall.

## Queda a criterio del builder

- Textos, colores y disposición fina dentro del diseño B.
- Etiquetas exactas del catálogo.
- Cómo se mapean `/pruebas` y `/pruebas/` (view controller o redirect).
