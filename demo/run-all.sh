#!/usr/bin/env bash
# Submits every task in the answer key and prints the expected verdict, the
# actual verdict, and whether they match. Exits non-zero on any mismatch, so CI
# fails if the harness ever disagrees with the key.
#
# submit only queues a run, so this works in three passes: queue every task,
# let one worker drain the queue, then read each verdict back with watch.
#
# Needs: PostgreSQL up (docker compose up -d postgres), the fixtures built
# (fixtures/build-fixtures.sh), ~/.m2 warmed, the jar built (mvn -B package),
# and a `python` on PATH with pytest installed. The worker uses its default
# lease and timeout ceiling, which cover every task in tasks/. It also verifies
# any other runs already waiting in the queue.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEY="$ROOT/demo/expected.tsv"
JAR="${FORGEGUARD_JAR:-$ROOT/target/forgeguard-0.1.0-SNAPSHOT.jar}"

if [ ! -f "$JAR" ]; then
  echo "run-all: no jar at $JAR; run mvn -B package first" >&2
  exit 2
fi

cd "$ROOT"

# 1. Queue every task. The run line is on stdout; logging and operator errors
#    go to stderr. A task that fails to queue keeps an empty id.
tasks=()
expected=()
ids=()
while IFS=$'\t' read -r task want; do
  case "$task" in ''|'#'*) continue ;; esac
  out="$(java -jar "$JAR" submit "tasks/$task.yaml" 2>/dev/null)"
  tasks+=("$task")
  expected+=("$want")
  ids+=("$(awk '$1 == "run" { print $2; exit }' <<<"$out")")
done < "$KEY"

# 2. Verify them. --drain exits once the queue is empty. Its progress goes to
#    stderr so the table below stays readable.
if ! java -jar "$JAR" worker --drain >&2; then
  echo "run-all: the worker exited non-zero; runs it did not finish show their state" >&2
fi

# 3. Read each verdict. The queue is drained, so --timeout 0 reads each run
#    once. A run still without a verdict shows its state, which never matches.
printf '%-14s %-25s %-25s %s\n' TASK EXPECTED ACTUAL MATCH
total=0
failed=0
for i in "${!tasks[@]}"; do
  total=$((total + 1))
  actual=NO_VERDICT
  if [ -n "${ids[$i]}" ]; then
    out="$(java -jar "$JAR" watch --timeout 0 "${ids[$i]}" 2>/dev/null)"
    actual="$(awk '$1 == "run" { v = $3 } END { print v }' <<<"$out")"
    actual="${actual:-NO_VERDICT}"
  fi

  if [ "$actual" = "${expected[$i]}" ]; then
    match=yes
  else
    match=NO
    failed=$((failed + 1))
  fi
  printf '%-14s %-25s %-25s %s\n' "${tasks[$i]}" "${expected[$i]}" "$actual" "$match"
done

echo
if [ "$failed" -ne 0 ]; then
  echo "$failed of $total verdicts do not match the answer key"
  exit 1
fi
echo "all $total verdicts match the answer key"
