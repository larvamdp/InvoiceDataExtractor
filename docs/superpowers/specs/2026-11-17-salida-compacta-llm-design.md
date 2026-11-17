# Salida compacta del LLM y tope de generación — diseño

Fecha: 2026-11-17 · Tamaño: cambio chico

## Por qué

En la notebook de prueba (CPU, sin GPU) un PDF de texto de 1 página no terminó en 15 min (504). Los logs de Ollama
mostraron que el prompt tenía 914 tokens pero el modelo generó ~7.300 tokens hasta llenar el contexto
(`truncated = 1`), y siguió trabajando 1 h 6 min después de que la API cortara. En CPU cada token de salida es caro
(~2 tok/s en esta máquina), así que el tiempo lo domina lo que el modelo escribe.

Objetivo: que una prueba local responda en ~60 s por documento, o falle rápido y con un mensaje claro.

## Objetivos / no objetivos

- **Objetivos:** acotar la generación del modelo; reducir la cantidad de tokens que escribe por documento.
- **No objetivos:** cambiar el contrato de la API (`valor`, `descripcion`, `fuente` siguen igual); cambiar de modelo;
  separar camino texto/imagen con modelos distintos (queda para después si no alcanza).
- **Anti-objetivo:** perder precisión de extracción a cambio de velocidad.

## Enfoques considerados

1. **Valores directos con nombre de campo (elegido).** Cada campo es el valor (`string|null`) en vez de
   `{"valor", "descripcion"}`; `clavesQr` pasa a un mapa aparte, solo si hay QR. Gran parte del ahorro, el modelo sigue
   viendo el nombre de cada dato.
2. **Filas del detalle como arreglos posicionales.** Más ahorro en facturas largas, pero un modelo chico confunde más el
   orden de columnas y el soporte de esquemas posicionales en Ollama es menos confiable. Descartado por riesgo de
   precisión.

Decisión del usuario: **no conservar** la explicación del modelo cuando hay varias candidatas (opción A); todas las
`descripcion` las arma Java.

## Diseño

### Formato que escribe el modelo

```json
{"cabecera": {"<campo>": "<valor tal como figura>" | null, ...},
 "detalle":  [{"<campo>": "<valor>" | null, ...}, ...],
 "clavesQr": {"<campoCabecera>": ["<claveQr>", ...], ...}}
```

- `clavesQr` existe en el esquema solo cuando hay QR; sus propiedades son los campos de cabecera, no requeridas
  (el modelo omite los que no corresponden), con ítems restringidos a `DatosQrArca.CLAVES`.
- El prompt de sistema elimina las reglas sobre `descripcion` y explica el nuevo formato y `clavesQr`.

### Parser y conciliación

- `ParserRespuestaLlm` lee el valor directo de cada campo y las claves desde `clavesQr.<campo>`; produce los mismos
  `ValorLlm` con `descripcion = null` (o "Dato no encontrado." si falta). Valores no textuales (número) se aceptan con
  `asText()`; objetos/arreglos en lugar de un valor cuentan como no encontrado.
- `ConciliadorQr`, `VerificadorTextual` y `ExtraccionService` no cambian: ya componen las notas de Java.

### Tope de generación

- Nueva propiedad `extractor.ollama.num-predict` (`EXTRACTOR_OLLAMA_NUMPREDICT`), default `2048`, enviada como
  `options.num_predict`. Pasa por compose, `.env.example` y README.
- Si Ollama responde `done_reason: "length"`, `OllamaLlmCliente` lanza una excepción que se mapea a **422** con mensaje
  "La respuesta del modelo superó el límite de tokens (EXTRACTOR_OLLAMA_NUMPREDICT)."; `ExtraccionService`
  **no reintenta** ese caso (con `temperature: 0` el reintento repite lo mismo). Log con `eval_count`, sin contenido.
- Sin `repeat_penalty`/`presence_penalty`: con JSON forzado penalizarían la puntuación y claves que el formato repite.

### Pruebas

- Unitarias actualizadas: `EsquemaRespuestaTest`, `ConstructorPromptTest`, `ParserRespuestaLlmTest`,
  `OllamaLlmClienteTest` (envía `num_predict`; `done_reason: length` → excepción), `ExtraccionServiceTest` (sin
  reintento por límite), `ManejadorErroresTest`/HTTP (422), y los stubs de respuesta del LLM en tests de integración.
- Medición: correr un documento sintético antes/después en la notebook y comparar `eval_count` y `tiemposMs.llm`.

## Queda a criterio del implementador

- Nombre de la nueva excepción (o reutilizar `RespuestaLlmInvalidaException` con un flag) y su texto exacto.
- Redacción del prompt de sistema.
