#!/usr/bin/env bash
# Submits every task in the answer key and prints the expected verdict, the
# actual verdict, and whether they match. Exits non-zero on any mismatch, so CI
# fails if the harness ever disagrees with the key.
#
# Needs: PostgreSQL up (docker compose up -d postgres), the fixtures built
# (fixtures/build-fixtures.sh), ~/.m2 warmed, the jar built (mvn -B package),
# and a `python` on PATH with pytest installed.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEY="$ROOT/demo/expected.tsv"
JAR="${FORGEGUARD_JAR:-$ROOT/target/forgeguard-0.1.0-SNAPSHOT.jar}"

if [ ! -f "$JAR" ]; then
  echo "run-all: no jar at $JAR; run mvn -B package first" >&2
  exit 2
fi

cd "$ROOT"
printf '%-14s %-25s %-25s %s\n' TASK EXPECTED ACTUAL MATCH
total=0
failed=0
while IFS=$'\t' read -r task expected; do
  case "$task" in ''|'#'*) continue ;; esac
  total=$((total + 1))

  # The verdict line is on stdout; logging and operator errors go to stderr.
  out="$(java -jar "$JAR" submit "tasks/$task.yaml" 2>/dev/null)"
  actual="$(awk '$1 == "run" { print $3; exit }' <<<"$out")"
  actual="${actual:-NO_VERDICT}"

  if [ "$actual" = "$expected" ]; then
    match=yes
  else
    match=NO
    failed=$((failed + 1))
  fi
  printf '%-14s %-25s %-25s %s\n' "$task" "$expected" "$actual" "$match"
done < "$KEY"

echo
if [ "$failed" -ne 0 ]; then
  echo "$failed of $total verdicts do not match the answer key"
  exit 1
fi
echo "all $total verdicts match the answer key"
