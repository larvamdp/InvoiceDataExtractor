#!/usr/bin/env bash
# Descarga un modelo de Ollama al volumen `ollama-modelos`.
# Es el UNICO paso que necesita acceso a internet; el servicio ollama corre luego en una red interna.
# Uso: ./scripts/descargar-modelo.sh qwen3-vl:4b
set -euo pipefail
MODELO="${1:?Uso: $0 <modelo>  (p. ej. qwen3-vl:4b)}"
docker volume create ollama-modelos >/dev/null
# Contenedor temporal en la red bridge (con salida a internet); --rm lo elimina al terminar.
docker run --rm -v ollama-modelos:/root/.ollama --network bridge --entrypoint sh ollama/ollama \
  -c 'ollama serve >/dev/null 2>&1 & for i in $(seq 1 30); do ollama list >/dev/null 2>&1 && break; sleep 1; done; ollama pull "$1" && ollama list' _ "$MODELO"
