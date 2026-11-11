#!/usr/bin/env bash
# Checks that each built fixture repo matches its recorded base commit and that
# every recorded patch applies to the repo it targets. Run after
# build-fixtures.sh; the printed SHAs are what two machines compare.
#
# Patches named py-*.patch target intervals-python; all others target
# intervals-java.
set -euo pipefail

OUT="${FORGEGUARD_FIXTURE_DIR:-/tmp/forgeguard-fixtures}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

check_base() {
  name="$1"
  expected="$(tr -d '[:space:]' < "$HERE/BASE_SHA.$name.txt")"
  actual="$(git -C "$OUT/$name" rev-parse HEAD)"
  if [ "$actual" != "$expected" ]; then
    echo "$name: base commit mismatch: expected $expected, got $actual" >&2
    exit 1
  fi
  echo "$name -> $actual"
}

check_base intervals-java
check_base intervals-python

for patch in "$HERE"/patches/*.patch; do
  case "$(basename "$patch")" in
    py-*) repo=intervals-python ;;
    *) repo=intervals-java ;;
  esac
  git -C "$OUT/$repo" apply --check "$patch"
  echo "applies cleanly to $repo: $(basename "$patch")"
done
