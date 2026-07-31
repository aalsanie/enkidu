#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "Usage: $0 <destination-directory> <version>" >&2
  exit 64
fi

destination="$1"
version="$2"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cd "$repo_root"

rm -rf "$destination"
mkdir -p "$destination"

required_files=(
  "enkidu-artifacts/build/libs/enkidu-artifacts-$version.jar"
  "enkidu-core/build/libs/enkidu-core-$version.jar"
  "enkidu-export/build/libs/enkidu-export-$version.jar"
  "enkidu-cli/build/libs/enkidu-cli-$version.jar"
  "enkidu-cli/build/distributions/enkidu-cli-$version.zip"
  "enkidu-cli/build/distributions/enkidu-cli-$version.tar"
)

for file in "${required_files[@]}"; do
  if [[ ! -f "$file" ]]; then
    echo "Missing release artifact: $file" >&2
    exit 1
  fi
  cp "$file" "$destination/"
done

mapfile -t plugin_archives < <(
  find enkidu-intellij-plugin/build/distributions \
    -maxdepth 1 \
    -type f \
    -name '*.zip' \
    -print \
    | sort
)

if [[ "${#plugin_archives[@]}" -ne 1 ]]; then
  echo "Expected exactly one IntelliJ plugin ZIP, found ${#plugin_archives[@]}." >&2
  printf '  %s\n' "${plugin_archives[@]}" >&2
  exit 1
fi

cp "${plugin_archives[0]}" "$destination/"

cli_jar="$destination/enkidu-cli-$version.jar"
manifest="$(unzip -p "$cli_jar" META-INF/MANIFEST.MF | tr -d '\r')"

grep -Fxq "Implementation-Version: $version" <<<"$manifest" || {
  echo "CLI JAR does not contain Implementation-Version: $version" >&2
  exit 1
}

grep -Fxq "Main-Class: io.enkidu.cli.EnkiduCli" <<<"$manifest" || {
  echo "CLI JAR does not contain the expected Main-Class." >&2
  exit 1
}

(
  cd "$destination"
  find . -maxdepth 1 -type f ! -name SHA256SUMS.txt -printf '%f\n' \
    | LC_ALL=C sort \
    | xargs -r sha256sum -- \
    > SHA256SUMS.txt
)

test -s "$destination/SHA256SUMS.txt"
