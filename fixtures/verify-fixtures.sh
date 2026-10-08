#!/usr/bin/env bash
# Checks that the built fixture repo matches the recorded base commit and that
# every recorded patch applies to it. Run after build-fixtures.sh; the printed
# SHA is what two machines compare.
set -euo pipefail

OUT="${FORGEGUARD_FIXTURE_DIR:-/tmp/forgeguard-fixtures}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$OUT/intervals-java"

expected="$(tr -d '[:space:]' < "$HERE/BASE_SHA.txt")"
actual="$(git -C "$REPO" rev-parse HEAD)"
if [ "$actual" != "$expected" ]; then
  echo "base commit mismatch: expected $expected, got $actual" >&2
  exit 1
fi
echo "intervals-java -> $actual"

for patch in "$HERE"/patches/*.patch; do
  git -C "$REPO" apply --check "$patch"
  echo "applies cleanly: $(basename "$patch")"
done
