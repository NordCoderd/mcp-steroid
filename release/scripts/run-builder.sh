#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE_TAG="${RELEASE_BUILDER_IMAGE:-mcp-steroid-release-builder:local}"
DOCKERFILE_PATH="$ROOT_DIR/release/docker/Dockerfile"

if [[ ! -S /var/run/docker.sock ]]; then
  echo "Docker socket not found at /var/run/docker.sock" >&2
  echo "Start Docker Desktop/daemon before running release builder." >&2
  exit 1
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
  -v "$ROOT_DIR:/workspace"
  -v mcp-steroid-intellij-platform-cache:/workspace/.intellijPlatform
  -w /workspace
  -v /var/run/docker.sock:/var/run/docker.sock
)

if [[ -d "${HOME}/.gradle" ]]; then
  docker_args+=(-v "${HOME}/.gradle:/root/.gradle")
fi

if [[ -d "${HOME}/.docker" ]]; then
  docker_args+=(-v "${HOME}/.docker:/root/.docker")
fi

if [[ -d "${HOME}/.config/gh" ]]; then
  docker_args+=(-v "${HOME}/.config/gh:/root/.config/gh")
fi

echo "Running in release builder container: $*"
docker "${docker_args[@]}" "$IMAGE_TAG" "$@"
