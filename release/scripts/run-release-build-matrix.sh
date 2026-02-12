#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

OUT_DIR="$ROOT_DIR/release/out"
mkdir -p "$OUT_DIR"

STABLE_PRODUCT="${RELEASE_STABLE_PRODUCT:-idea}"
STABLE_VERSION="${RELEASE_STABLE_VERSION:-2025.3}"
EAP_PRODUCT="${RELEASE_EAP_PRODUCT:-idea}"
EAP_VERSION="${RELEASE_EAP_VERSION:-2026.1}"

GRADLE_COMMON=(./gradlew --no-daemon --stacktrace)

select_single_distribution_zip() {
  local dist_dir="$ROOT_DIR/build/distributions"
  if [[ ! -d "$dist_dir" ]]; then
    echo "Distribution directory missing after build: $dist_dir" >&2
    exit 1
  fi

  shopt -s nullglob
  local matches=("$dist_dir"/mcp-steroid-*.zip)
  shopt -u nullglob

  if [[ "${#matches[@]}" -ne 1 ]]; then
    echo "Expected exactly one plugin ZIP matching mcp-steroid-*.zip in $dist_dir, found ${#matches[@]}." >&2
    if [[ "${#matches[@]}" -gt 0 ]]; then
      echo "Matching ZIP files:" >&2
      printf '  %s\n' "${matches[@]}" >&2
    fi
    echo "Current contents of $dist_dir:" >&2
    ls -lah "$dist_dir" >&2 || true
    exit 1
  fi

  printf '%s\n' "${matches[0]}"
}

echo "== Stage 1: stable build (product=$STABLE_PRODUCT version=$STABLE_VERSION) =="
"${GRADLE_COMMON[@]}" \
  clean build buildPlugin \
  -Pmcp.platform.product="$STABLE_PRODUCT" \
  -Pmcp.platform.version="$STABLE_VERSION"

stable_zip="$(select_single_distribution_zip)"

stable_copy="$OUT_DIR/plugin-${STABLE_PRODUCT}-${STABLE_VERSION}.zip"
cp "$stable_zip" "$stable_copy"
echo "Stable plugin ZIP saved: $stable_copy"

echo "== Stage 2: EAP build (product=$EAP_PRODUCT version=$EAP_VERSION) =="
"${GRADLE_COMMON[@]}" \
  clean build buildPlugin \
  -Pmcp.platform.product="$EAP_PRODUCT" \
  -Pmcp.platform.version="$EAP_VERSION"

echo "== Stage 3: selected integration matrix [IDEA,PyCharm] x [stable,EAP] =="
"${GRADLE_COMMON[@]}" :test-integration:testReleaseSmokeMatrix

cat > "$OUT_DIR/build-summary.txt" <<EOF
stable_product=$STABLE_PRODUCT
stable_version=$STABLE_VERSION
stable_plugin_zip=$stable_copy
eap_product=$EAP_PRODUCT
eap_version=$EAP_VERSION
integration_matrix_task=:test-integration:testReleaseSmokeMatrix
timestamp_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

echo "Build summary written: $OUT_DIR/build-summary.txt"
