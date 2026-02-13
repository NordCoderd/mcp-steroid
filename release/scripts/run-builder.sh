#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE_TAG="${RELEASE_BUILDER_IMAGE:-mcp-steroid-release-builder:local}"
DOCKERFILE_PATH="$ROOT_DIR/release/docker/Dockerfile"
RELEASE_BUILDER_ISOLATE_WORKSPACE="${RELEASE_BUILDER_ISOLATE_WORKSPACE:-1}"

normalize_bool() {
  local raw="${1:-}"
  local normalized
  normalized="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  case "$normalized" in
    1|true|yes|on)
      printf '1\n'
      ;;
    0|false|no|off)
      printf '0\n'
      ;;
    *)
      echo "Unsupported boolean value '$raw' (expected true/false or 1/0)" >&2
      exit 2
      ;;
  esac
}

RELEASE_BUILDER_ISOLATE_WORKSPACE="$(normalize_bool "$RELEASE_BUILDER_ISOLATE_WORKSPACE")"

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

check_no_conflicting_local_gradle_processes() {
  local matches
  matches="$(ps -ax -o pid= -o command= | grep -F "$ROOT_DIR" | grep -E 'gradle-wrapper\.jar|Gradle Test Executor|worker\.org\.gradle\.process\.internal\.worker\.GradleWorkerMain' || true)"
  if [[ -z "$matches" ]]; then
    return 0
  fi

  echo "Detected local Gradle/test JVM processes using this repository path." >&2
  echo "They race with the builder container because /workspace is bind-mounted from host." >&2
  echo "Stop those processes (for example: ./gradlew --stop, then terminate active ./gradlew test) and rerun." >&2
  echo "Conflicting processes:" >&2
  echo "$matches" >&2
  exit 1
}

select_mcp_test_port() {
  MCP_STEROID_TEST_PORT_SPAN="${MCP_STEROID_TEST_PORT_SPAN:-10}"
  export MCP_STEROID_TEST_PORT_SPAN

  if [[ -n "${MCP_STEROID_TEST_PORT:-}" ]]; then
    MCP_STEROID_TEST_PORT_END=$((MCP_STEROID_TEST_PORT + MCP_STEROID_TEST_PORT_SPAN - 1))
    export MCP_STEROID_TEST_PORT_END
    return 0
  fi

  MCP_STEROID_TEST_PORT="$(python3 - <<'PY'
import os
import socket

preferred_port = 17820
port_span = int(os.environ.get("MCP_STEROID_TEST_PORT_SPAN", "10"))

def is_range_free(start_port: int, span: int) -> bool:
    sockets = []
    try:
        for offset in range(span):
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.bind(("0.0.0.0", start_port + offset))
            sockets.append(sock)
        return True
    except OSError:
        return False
    finally:
        for sock in sockets:
            sock.close()

if is_range_free(preferred_port, port_span):
    print(preferred_port)
else:
    start_port = 20000
    max_port = 65000 - port_span
    for candidate in range(start_port, max_port):
        if is_range_free(candidate, port_span):
            print(candidate)
            break
    else:
        raise SystemExit("No free TCP port range found for MCP tests")
PY
)"
  export MCP_STEROID_TEST_PORT
  MCP_STEROID_TEST_PORT_END=$((MCP_STEROID_TEST_PORT + MCP_STEROID_TEST_PORT_SPAN - 1))
  export MCP_STEROID_TEST_PORT_END
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
if [[ "$RELEASE_BUILDER_ISOLATE_WORKSPACE" != "1" ]]; then
  check_no_conflicting_local_gradle_processes
fi
select_mcp_test_port

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
  -e MCP_STEROID_TEST_PORT
  -e MCP_STEROID_TEST_PORT_SPAN
  -e DOCKER_HOST=unix:///var/run/docker.sock
  -e DOCKER_BUILDKIT=0
  -p "${MCP_STEROID_TEST_PORT}-${MCP_STEROID_TEST_PORT_END}:${MCP_STEROID_TEST_PORT}-${MCP_STEROID_TEST_PORT_END}"
  -v /var/run/docker.sock:/var/run/docker.sock
)

if [[ "$RELEASE_BUILDER_ISOLATE_WORKSPACE" == "1" ]]; then
  docker_args+=(
    -v "$ROOT_DIR:/host-workspace"
    -e MCP_STEROID_DOCKER_HOST_PATH_MAP=/workspace="$ROOT_DIR"
    -v mcp-steroid-intellij-platform-cache:/workspace/.intellijPlatform
    -w /workspace
  )
else
  docker_args+=(
    -v "$ROOT_DIR:/workspace"
    -v mcp-steroid-intellij-platform-cache:/workspace/.intellijPlatform
    -w /workspace
  )
fi

forwarded_envs=()
forward_env_if_set OPENAI_API_KEY
forward_env_if_set CODEX_API_KEY
forward_env_if_set ANTHROPIC_API_KEY
forward_env_if_set GEMINI_API_KEY
forward_env_if_set GOOGLE_API_KEY

if [[ -d "${HOME}/.gradle" ]]; then
  docker_args+=(-v "${HOME}/.gradle:/root/.gradle")
fi

if [[ -d "${HOME}/.config/gh" ]]; then
  docker_args+=(-v "${HOME}/.config/gh:/root/.config/gh")
fi

if [[ "${#forwarded_envs[@]}" -gt 0 ]]; then
  echo "Forwarding API key environment variables to builder container: ${forwarded_envs[*]}"
else
  echo "No API key environment variables detected for builder container."
fi
echo "Using MCP test port range: ${MCP_STEROID_TEST_PORT}-${MCP_STEROID_TEST_PORT_END}"

if [[ "$RELEASE_BUILDER_ISOLATE_WORKSPACE" == "1" ]]; then
  quoted_cmd=""
  for arg in "$@"; do
    printf -v escaped_arg '%q' "$arg"
    quoted_cmd+="$escaped_arg "
  done
  wrapper_cmd="set -euo pipefail; rsync -a --delete --exclude '.intellijPlatform/' --exclude 'build/' --exclude '.gradle/' /host-workspace/ /workspace/; cd /workspace; $quoted_cmd; mkdir -p /host-workspace/release/out; rsync -a --delete /workspace/release/out/ /host-workspace/release/out/"
  echo "Running in release builder container (isolated workspace copy): $*"
  docker "${docker_args[@]}" "$IMAGE_TAG" bash -lc "$wrapper_cmd"
else
  echo "Running in release builder container: $*"
  docker "${docker_args[@]}" "$IMAGE_TAG" "$@"
fi
