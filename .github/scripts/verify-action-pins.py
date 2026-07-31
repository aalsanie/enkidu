#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
GITHUB_DIR = ROOT / ".github"
WORKFLOW_SUFFIXES = {".yml", ".yaml"}
FULL_COMMIT_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
USES_LINE = re.compile(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)")

violations: list[str] = []

for path in sorted(GITHUB_DIR.rglob("*")):
    if not path.is_file() or path.suffix.lower() not in WORKFLOW_SUFFIXES:
        continue

    relative = path.relative_to(ROOT)
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        match = USES_LINE.match(line)
        if match is None:
            continue

        reference = match.group(1).strip("'\"")

        if reference.startswith("./"):
            continue

        if reference.startswith("docker://"):
            if "@sha256:" not in reference:
                violations.append(
                    f"{relative}:{line_number}: Docker action must be pinned by sha256 digest: {reference}"
                )
            continue

        if "@" not in reference:
            violations.append(f"{relative}:{line_number}: action has no immutable ref: {reference}")
            continue

        action, ref = reference.rsplit("@", 1)
        if not action or FULL_COMMIT_SHA.fullmatch(ref) is None:
            violations.append(
                f"{relative}:{line_number}: action must use a full 40-character commit SHA: {reference}"
            )

if violations:
    print("Mutable or invalid GitHub Action references found:", file=sys.stderr)
    for violation in violations:
        print(f"  - {violation}", file=sys.stderr)
    raise SystemExit(1)

print("All external GitHub Actions are pinned to immutable commit SHAs.")
