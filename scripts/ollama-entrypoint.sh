#!/bin/sh
set -eu

MODEL="${OLLAMA_MODEL:-qwen2.5-coder:1.5b}"

echo "INFO: Starting Ollama server on ${OLLAMA_HOST:-0.0.0.0:11434}"
ollama serve &
OLLAMA_PID="$!"

cleanup() {
  echo "INFO: Stopping Ollama server..."
  kill "${OLLAMA_PID}" 2>/dev/null || true
  wait "${OLLAMA_PID}" 2>/dev/null || true
}
trap cleanup INT TERM

echo "INFO: Waiting for Ollama API..."
for _ in $(seq 1 60); do
  if OLLAMA_HOST=127.0.0.1:11434 ollama list >/dev/null 2>&1; then
    echo "INFO: Ollama API is ready."
    break
  fi
  sleep 2
done

if ! OLLAMA_HOST=127.0.0.1:11434 ollama list >/dev/null 2>&1; then
  echo "ERROR: Ollama API did not become ready in time." >&2
  exit 1
fi

if OLLAMA_HOST=127.0.0.1:11434 ollama show "${MODEL}" >/dev/null 2>&1; then
  echo "INFO: Ollama model already exists: ${MODEL}"
else
  echo "INFO: Pulling Ollama model: ${MODEL}"
  OLLAMA_HOST=127.0.0.1:11434 ollama pull "${MODEL}"
fi

echo "INFO: Ollama is ready. model=${MODEL}"
wait "${OLLAMA_PID}"
