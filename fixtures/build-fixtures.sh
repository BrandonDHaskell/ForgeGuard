#!/usr/bin/env bash
# Materializes the vendored fixture sources into local Git repos that JGit can
# clone over file://. Identity and dates are pinned so commit SHAs are identical
# on every machine, which keeps run records comparable across laptops.
set -euo pipefail

OUT="${FORGEGUARD_FIXTURE_DIR:-/tmp/forgeguard-fixtures}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export GIT_AUTHOR_NAME="ForgeGuard Fixture"
export GIT_AUTHOR_EMAIL="fixture@cleansweep.dev"
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
export GIT_AUTHOR_DATE="2026-01-01T00:00:00Z"
export GIT_COMMITTER_DATE="$GIT_AUTHOR_DATE"

build() {
  name="$1"
  rm -rf "$OUT/$name"
  mkdir -p "$OUT/$name"
  cp -r "$HERE/$name/." "$OUT/$name/"
  git -C "$OUT/$name" init -q -b main
  git -C "$OUT/$name" add -A
  git -C "$OUT/$name" commit -q -m "fixture: $name baseline"
  git -C "$OUT/$name" tag v1.0
  echo "$name -> $(git -C "$OUT/$name" rev-parse HEAD)"
}

mkdir -p "$OUT"
build intervals-java
