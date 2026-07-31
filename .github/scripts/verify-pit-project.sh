#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
pit_root="$repo_root/docs/enkidu_pit_project"
reports_dir="$pit_root/build/enkidu/ci"

cd "$repo_root"

command -v python3 >/dev/null 2>&1 || {
  echo "python3 is required for report validation." >&2
  exit 1
}

chmod +x gradlew

version="$(./gradlew --no-daemon --quiet printVersion | tail -n 1 | tr -d '\r')"
test -n "$version"

./gradlew --no-daemon --stacktrace :enkidu-cli:installDist
./gradlew --no-daemon --stacktrace -p "$pit_root" clean enkiduPit

cli="$repo_root/enkidu-cli/build/install/enkidu-cli/bin/enkidu-cli"
target="$pit_root/app/build/libs/app.jar"

test -x "$cli"
test -f "$target"

rm -rf "$reports_dir"
mkdir -p "$reports_dir"

validate_report() {
  local report="$1"
  local expected_types_csv="$2"
  local expected_version="$3"
  local mode="$4"

  python3 - "$report" "$expected_types_csv" "$expected_version" "$mode" <<'PY'
import json
import sys
from pathlib import Path

report_path = Path(sys.argv[1])
expected_types = {item for item in sys.argv[2].split(",") if item}
expected_version = sys.argv[3]
mode = sys.argv[4]

with report_path.open("r", encoding="utf-8") as handle:
    payload = json.load(handle)

actual_version = payload.get("tool", {}).get("version")
if actual_version != expected_version:
    raise SystemExit(
        f"{report_path}: tool.version={actual_version!r}; expected {expected_version!r}"
    )

failures = payload.get("failures", [])
if not isinstance(failures, list):
    raise SystemExit(f"{report_path}: failures must be a JSON array")

def is_demo_failure(failure: object) -> bool:
    if not isinstance(failure, dict):
        return False

    owner = str((failure.get("symbol") or {}).get("owner") or "")
    if owner.startswith("demo/") or owner.startswith("demo."):
        return True

    spi = (failure.get("evidence") or {}).get("spi") or {}
    service = str(spi.get("service") or "")
    provider = str(spi.get("provider") or "")
    return service.startswith("demo.") or provider.startswith("demo.")

demo_failures = [failure for failure in failures if is_demo_failure(failure)]

if mode == "baseline":
    if demo_failures:
        kinds = sorted({str(item.get("type")) for item in demo_failures})
        raise SystemExit(
            f"{report_path}: baseline unexpectedly contains demo linkage failures: {kinds}"
        )
    raise SystemExit(0)

actual_types = {
    str(failure.get("type"))
    for failure in demo_failures
    if isinstance(failure, dict)
}
if not actual_types.intersection(expected_types):
    raise SystemExit(
        f"{report_path}: expected one of {sorted(expected_types)}, "
        f"found demo failure types {sorted(actual_types)}"
    )
PY
}

run_doctor() {
  local name="$1"
  local classpath_manifest="$2"
  local fail_on="$3"
  local expected_exit="$4"
  local expected_types="$5"
  local mode="$6"
  local report="$reports_dir/$name.json"

  set +e
  "$cli" doctor \
    --targets "$target" \
    --classpath-file "$classpath_manifest" \
    --format json \
    --output "$report" \
    --fail-on "$fail_on"
  local exit_code=$?
  set -e

  if [[ "$exit_code" -ne "$expected_exit" ]]; then
    echo "$name: expected Enkidu exit code $expected_exit, got $exit_code" >&2
    exit 1
  fi

  validate_report "$report" "$expected_types" "$version" "$mode"
}

run_doctor \
  "baseline" \
  "$pit_root/build/enkidu/compareA.classpath.txt" \
  "none" \
  0 \
  "" \
  "baseline"

run_doctor \
  "missing-method" \
  "$pit_root/build/enkidu/missing-method.classpath.txt" \
  "any" \
  2 \
  "MISSING_METHOD,DESCRIPTOR_MISMATCH" \
  "scenario"

run_doctor \
  "illegal-access" \
  "$pit_root/build/enkidu/illegal-access.classpath.txt" \
  "any" \
  2 \
  "ILLEGAL_ACCESS_RISK" \
  "scenario"

run_doctor \
  "shadowing" \
  "$pit_root/build/enkidu/shadowing.classpath.txt" \
  "any" \
  2 \
  "DUPLICATE_CLASS_SHADOWING" \
  "scenario"

run_doctor \
  "spi-broken" \
  "$pit_root/build/enkidu/spi-broken.classpath.txt" \
  "any" \
  2 \
  "SPI_PROVIDER_BROKEN" \
  "scenario"

"$cli" doctor \
  --targets "$target" \
  --classpath-file "$pit_root/build/enkidu/missing-method.classpath.txt" \
  --format json \
  --output "$reports_dir/missing-method-parallel.json" \
  --fail-on none \
  --jar-scan-cache-dir "$reports_dir/cache" \
  --jar-scan-parallelism 2 \
  --target-scan-parallelism 2 \
  --max-in-flight-target-classes 4

cmp "$reports_dir/missing-method.json" "$reports_dir/missing-method-parallel.json"

targets_manifest="$reports_dir/targets.txt"
printf '%s\n' "$target" > "$targets_manifest"

compare_report="$reports_dir/compare.json"
"$cli" compare \
  --targets "$targets_manifest" \
  --classpath-a "$pit_root/build/enkidu/compareA.classpath.txt" \
  --classpath-b "$pit_root/build/enkidu/compareB.classpath.txt" \
  --label-a working \
  --label-b regressed \
  --out "$compare_report"

python3 - "$compare_report" "$version" <<'PY'
import json
import sys
from pathlib import Path

report_path = Path(sys.argv[1])
expected_version = sys.argv[2]

with report_path.open("r", encoding="utf-8") as handle:
    payload = json.load(handle)

actual_version = payload.get("tool", {}).get("version")
if actual_version != expected_version:
    raise SystemExit(
        f"{report_path}: tool.version={actual_version!r}; expected {expected_version!r}"
    )

summary = payload.get("summary", {})
regressions = summary.get("regressions")
if not isinstance(regressions, int) or regressions < 1:
    raise SystemExit(
        f"{report_path}: expected at least one classpath regression, found {regressions!r}"
    )
PY

echo "Enkidu pit-project end-to-end validation passed."
