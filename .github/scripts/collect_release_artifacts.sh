#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

mkdir -p dist

# --- Plugin ZIP ---
shopt -s nullglob
plugin_zips=(enkidu-intellij-plugin/build/distributions/*.zip)
if (( ${#plugin_zips[@]} == 0 )); then
  echo "::error::No plugin ZIP found under enkidu-intellij-plugin/build/distributions/*.zip"
  ls -la enkidu-intellij-plugin/build || true
  ls -la enkidu-intellij-plugin/build/distributions || true
  exit 1
fi

for z in "${plugin_zips[@]}"; do
  cp -v "${z}" dist/
done

# --- CLI distribution tarball (installDist output) ---
cli_install_dir="enkidu-cli/build/install/enkidu-cli"
if [[ ! -d "${cli_install_dir}" ]]; then
  echo "::error::CLI installDist output missing: ${cli_install_dir}"
  ls -la enkidu-cli/build || true
  ls -la enkidu-cli/build/install || true
  exit 1
fi

# The release tag is passed in as vX.Y.Z, we embed it in the tarball name.
tag="${GITHUB_REF_NAME:-unknown}"
tarball="dist/enkidu-cli-${tag}.tar.gz"
tar -C "enkidu-cli/build/install" -czf "${tarball}" "enkidu-cli"

# --- Checksums ---
( cd dist && sha256sum * > SHA256SUMS.txt )

echo "Dist contents:"
ls -la dist
