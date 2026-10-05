#!/usr/bin/env bash
# Poll the on-device LocalSeek index size while a long indexing run is in progress.
#
# Each iteration (max 24, 15 minutes apart):
#   1. require exactly one device in `adb devices`;
#   2. skip the iteration if a LocalSeek job is currently running in JobScheduler,
#      because `am instrument` force-stops the target package and would kill indexing;
#   3. run SampleTargetsInstrumentedTest and read ONLY the INDEX_COUNTS line it logs
#      (tag "SampleTargets") from logcat;
#   4. append `timestamp,files,apps,contacts,chunks,extensions` to index_progress.csv.
# Exits early once the chunk count is unchanged for 3 consecutive iterations.
#
# Never pulls, pushes, installs or deletes anything on the device, and never writes
# titles: the INDEX_COUNTS line contains counts only. Per-iteration status goes to
# monitor_index.log (git-ignored via *.log).

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CSV="$SCRIPT_DIR/index_progress.csv"
LOG="$SCRIPT_DIR/monitor_index.log"
PKG="com.augt.localseek"
TEST_CLASS="com.augt.localseek.eval.SampleTargetsInstrumentedTest"
RUNNER="com.augt.localseek.test/androidx.test.runner.AndroidJUnitRunner"
LOG_TAG="SampleTargets"

MAX_ITERATIONS="${MAX_ITERATIONS:-24}"
SLEEP_SECONDS="${SLEEP_SECONDS:-900}"
STABLE_LIMIT=3

[ -f "$CSV" ] || echo "timestamp,files,apps,contacts,chunks,extensions" > "$CSV"

log() { echo "$(date -Iseconds) $*" | tee -a "$LOG"; }

device_count() {
  adb devices | awk 'NR > 1 && $2 == "device" { n++ } END { print n + 0 }'
}

# True if a LocalSeek job is listed under "Active jobs:" in the JobScheduler dump.
indexing_active() {
  adb shell dumpsys jobscheduler | awk -v pkg="$PKG/" '
    /^Active jobs:/ { inside = 1; next }
    inside && /^[^ ]/ { inside = 0 }
    inside && index($0, pkg) { found = 1 }
    END { exit found ? 0 : 1 }'
}

prev_chunks=""
stable=0

for ((i = 1; i <= MAX_ITERATIONS; i++)); do
  ts="$(date -Iseconds)"
  status="OK"
  files=NA apps=NA contacts=NA chunks=NA exts=NA

  n="$(device_count)"
  if [ "$n" != "1" ]; then
    status="DEVICES=$n"
  elif indexing_active; then
    status="SKIPPED_INDEXING_ACTIVE"
  else
    start_epoch="$(date +%s)"
    out="$(adb shell am instrument -w -r -e class "$TEST_CLASS" "$RUNNER" 2>&1)"
    if echo "$out" | grep -q "ClassNotFoundException\|Unable to find instrumentation"; then
      status="TEST_APK_MISSING"
    elif echo "$out" | grep -q "^OK (1 test)"; then
      status="OK"
    else
      status="TEST_FAILED"
    fi
    # Latest INDEX_COUNTS line from this run; epoch timestamps guard against stale lines.
    line="$(adb logcat -d -v epoch -s "$LOG_TAG:I" | grep "INDEX_COUNTS" | tail -n 1)"
    line_epoch="$(echo "$line" | awk '{ printf "%d", $1 }')"
    if [ -n "$line" ] && [ "${line_epoch:-0}" -ge "$((start_epoch - 120))" ]; then
      files="$(echo "$line" | sed -n 's/.*files=\([0-9]*\).*/\1/p')"
      apps="$(echo "$line" | sed -n 's/.*apps=\([0-9]*\).*/\1/p')"
      contacts="$(echo "$line" | sed -n 's/.*contacts=\([0-9]*\).*/\1/p')"
      chunks="$(echo "$line" | sed -n 's/.*chunks=\([0-9]*\).*/\1/p')"
      exts="$(echo "$line" | sed -n 's/.*extensions=\([0-9]*\).*/\1/p')"
    else
      status="$status,NO_FRESH_COUNTS"
    fi
  fi

  echo "$ts,$files,$apps,$contacts,$chunks,$exts" >> "$CSV"
  log "iteration $i/$MAX_ITERATIONS status=$status chunks=$chunks"

  if [ "$chunks" != "NA" ]; then
    if [ "$chunks" = "$prev_chunks" ]; then
      stable=$((stable + 1))
    else
      stable=0
    fi
    prev_chunks="$chunks"
    if [ "$stable" -ge "$STABLE_LIMIT" ]; then
      log "chunk count unchanged for $STABLE_LIMIT consecutive iterations; exiting"
      exit 0
    fi
  fi

  if [ "$i" -lt "$MAX_ITERATIONS" ]; then
    sleep "$SLEEP_SECONDS"
  fi
done
