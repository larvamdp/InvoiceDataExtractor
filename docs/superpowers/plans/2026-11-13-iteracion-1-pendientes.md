# Iteración 1 — Pendientes

Lo que quedó abierto al cerrar la implementación de la iteración 1. Los items ya resueltos en la revisión final (temporales en disco, normalización de decimales, tipos del QR, prompt injection, bordes del CUIT, fondo negro en PNG, mensaje 413, columnas del benchmark, etc.) no se listan.

## Requiere acción del usuario

- **Docker Compose no instalado**: `docker-compose.yml` y `docker-compose.gpu.yml` nunca se ejecutaron (solo se validó la topología equivalente con `docker run`). Instalar `docker-compose-v2`, borrar contenedores/redes manuales (`docker rm -f ollama extractor-api; docker network rm ia publica`) y correr `docker compose config` y `docker compose up -d --build`.
- **Tag `ollama/ollama:0.40.16`** tomado del contenedor en ejecución; verificar que existe en Docker Hub.
- **Benchmark completo**: solo se corrió `retail-a` (3 documentos) en la notebook; los 3 dieron 504 a los 600 s. Correr el dataset completo y con concurrencia 2–3 en el servidor con GPU.

## Diferidos de la revisión final

- #10 `ParserRespuestaLlm` descarta `clavesQr` cuando `valor` es null: con una foto borrosa no se usa el QR aunque el modelo lo haya mapeado.
- #14 Las imágenes de página quedan retenidas mientras la solicitud espera en la cola (≈250–300 MB por escaneo de 10 páginas); no hay tope de solicitudes en vuelo.
- #15 Cada página se rasteriza dos veces (QR a 200 DPI, LLM a 150 DPI); se podría reutilizar.
- #18 Pulido: `Locale.ROOT` en `Comparador`; mensaje engañoso para `tipo` inválido; 415 genérico para la parte `solicitud`.

## Menores de las revisiones por tarea

- QR: JSON sin `cuit`/`nroCmp` se considera presente; decodificador Base64 MIME permisivo.
- `ProcesadorDocumento` mezcla PDF y parser EXIF (≈230 líneas); orientaciones EXIF distintas de 6 sin tests; mapa de tiempos mutable.
- Tests faltantes del prompt/parser: enum `clavesQr` == `CLAVES`, PNG en Base64, raíz no-objeto.
- Cliente Ollama: timeout de conexión fijo (10 s); todo 4xx se mapea a 503.
- Conciliación: mapeo multi-clave sobre IMPORTE separa "15000.00" en grupos; falsos positivos de números chicos en la verificación textual (limitación documentada).
- Servicio: test de "verificar" en filas de detalle; aserciones sobre valores de tiempos; categoría en el log de reintento.
- API: mensaje con `'null'` para nombre nulo; asserts de `detail` en 400; validación de `camposDetalle`; test de parte `documento` faltante; 405/415 sin cabeceras `Allow`/`Accept`.
- `descargar-modelo.sh`: agregar `--init`.
- Dataset: test de consistencia texto impreso vs `esperado`; legibilidad del QR en formas degradadas de nd-b/energia-b; etiquetas "CUIT" ambiguas en retail-a; código muerto `dec()`.
- Benchmark: dilución por filas extra; acumulador `int[4]`.
