#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE_TAG="${RELEASE_BUILDER_IMAGE:-mcp-steroid-release-builder:local}"
DOCKERFILE_PATH="$ROOT_DIR/release/docker/Dockerfile"

load_env_from_files_if_missing() {
  local env_name="$1"
  shift

  if [[ -n "${!env_name:-}" ]]; then
    return 0
  fi

  local file_path
  local value
  for file_path in "$@"; do
    if [[ -f "$file_path" ]]; then
      value="$(tr -d '\r\n' < "$file_path")"
      if [[ -n "$value" ]]; then
        export "$env_name=$value"
        return 0
      fi
    fi
  done
}

forward_env_if_set() {
  local env_name="$1"
  if [[ -n "${!env_name:-}" ]]; then
    docker_args+=(-e "$env_name")
    forwarded_envs+=("$env_name")
  fi
}

if [[ ! -S /var/run/docker.sock ]]; then
  echo "Docker socket not found at /var/run/docker.sock" >&2
  echo "Start Docker Desktop/daemon before running release builder." >&2
  exit 1
fi

load_env_from_files_if_missing OPENAI_API_KEY "${HOME}/.openai"
if [[ -z "${CODEX_API_KEY:-}" && -n "${OPENAI_API_KEY:-}" ]]; then
  export CODEX_API_KEY="$OPENAI_API_KEY"
fi
load_env_from_files_if_missing ANTHROPIC_API_KEY "${HOME}/.anthropic"
load_env_from_files_if_missing GEMINI_API_KEY "${HOME}/.vertes" "${HOME}/.vertex"
if [[ -z "${GOOGLE_API_KEY:-}" && -n "${GEMINI_API_KEY:-}" ]]; then
  export GOOGLE_API_KEY="$GEMINI_API_KEY"
fi

if [[ $# -eq 0 ]]; then
  set -- bash release/scripts/run-release-build-matrix.sh
fi

echo "Building release builder image: $IMAGE_TAG"
docker build \
  --file "$DOCKERFILE_PATH" \
  --tag "$IMAGE_TAG" \
  "$ROOT_DIR"

docker_args=(
  run
  --rm
  -t
  -e RELEASE_STABLE_PRODUCT
  -e RELEASE_STABLE_VERSION
  -e RELEASE_EAP_PRODUCT
  -e RELEASE_EAP_VERSION
  -v "$ROOT_DIR:/workspace"
  -v mcp-steroid-intellij-platform-cache:/workspace/.intellijPlatform
  -w /workspace
  -v /var/run/docker.sock:/var/run/docker.sock
)

forwarded_envs=()
forward_env_if_set OPENAI_API_KEY
forward_env_if_set CODEX_API_KEY
forward_env_if_set ANTHROPIC_API_KEY
forward_env_if_set GEMINI_API_KEY
forward_env_if_set GOOGLE_API_KEY

if [[ -d "${HOME}/.gradle" ]]; then
  docker_args+=(-v "${HOME}/.gradle:/root/.gradle")
fi

if [[ -d "${HOME}/.docker" ]]; then
  docker_args+=(-v "${HOME}/.docker:/root/.docker")
fi

if [[ -d "${HOME}/.config/gh" ]]; then
  docker_args+=(-v "${HOME}/.config/gh:/root/.config/gh")
fi

if [[ "${#forwarded_envs[@]}" -gt 0 ]]; then
  echo "Forwarding API key environment variables to builder container: ${forwarded_envs[*]}"
else
  echo "No API key environment variables detected for builder container."
fi

echo "Running in release builder container: $*"
docker "${docker_args[@]}" "$IMAGE_TAG" "$@"
