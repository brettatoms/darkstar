#!/usr/bin/env bash
#
# Soak-test one transport at one heap size. See dev/soak.clj for the findings and
# the caveats — in particular, on a single macOS host this is bounded by ephemeral
# ports (~16,384) rather than by the server, and heap has never been the binding
# constraint.
#
#   bin/soak.sh <jetty|jetty-vt|http-kit> <heap> [drivers] [per-driver] [hold-secs]
#
#   bin/soak.sh http-kit 2g
#   bin/soak.sh jetty-vt 4g 1 20000 300
#   bin/soak.sh http-kit 2g 4 15000 300 127.0.0.2,127.0.0.3,127.0.0.4,127.0.0.5
#
# The sixth argument is a comma-separated list of local addresses to bind drivers
# to, one per driver. That is the only way to exceed ~16k on a single host, and it
# needs the aliases to exist first:
#
#   sudo ifconfig lo0 alias 127.0.0.2 up
#
# Runs ONE config at a time and tears down completely between runs, so a previous
# server never competes for CPU or holds the port.

set -uo pipefail

SRV=${1:?server: jetty | jetty-vt | http-kit}
HEAP=${2:?heap: e.g. 2g}
NDRIVERS=${3:-1}
PER=${4:-15000}
HOLD=${5:-240}
ADDRS=${6:-}

PORT=3100
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${SOAK_OUT:-/tmp}"
cd "$REPO"

cleanup() {
  pkill -9 -f "soak drive-once" 2>/dev/null
  pkill -9 -f "soak serve" 2>/dev/null
  # Kill by port too: a Jetty that died of thread exhaustion keeps the socket, which
  # made every subsequent config fail to bind with "Address already in use".
  for p in $(lsof -ti :$PORT 2>/dev/null); do kill -9 "$p" 2>/dev/null; done
  for _ in $(seq 1 40); do lsof -ti :$PORT >/dev/null 2>&1 || break; sleep 1; done
}
trap cleanup EXIT
cleanup

# The shell's soft limit; note this does NOT lift macOS's launchctl maxfiles cap,
# which is what actually pins a JVM around ~4,900 descriptors.
ulimit -n 200000 2>/dev/null || true

SRV_LOG="$OUT/soak-$SRV-$HEAP.log"
clojure -J-Xmx"$HEAP" -J-Xms"$HEAP" -M:soak -m soak serve "$SRV" > "$SRV_LOG" 2>&1 &

for _ in $(seq 1 90); do sleep 1; grep -qa READY "$SRV_LOG" 2>/dev/null && break; done
if ! grep -qa READY "$SRV_LOG"; then
  echo "=== $SRV @ $HEAP: SERVER FAILED TO START ==="
  grep -a "rror\|xception" "$SRV_LOG" | head -3
  exit 1
fi

IFS=',' read -r -a ADDR_ARR <<< "$ADDRS"
for d in $(seq 1 "$NDRIVERS"); do
  ADDR="${ADDR_ARR[$((d-1))]:-}"
  ( ulimit -n 200000 2>/dev/null
    clojure -M:soak -m soak drive-once "$PER" $ADDR \
      > "$OUT/soak-drv-$SRV-$HEAP-$d.log" 2>&1 ) &
  sleep 5
done

# Hold, so the reported figure is a plateau rather than a high-water mark over a
# ramp. Sockets are never closed by the drivers.
sleep "$HOLD"

echo "=== $SRV @ $HEAP — $NDRIVERS driver(s) x $PER, held ${HOLD}s ==="
grep -a "^server=" "$SRV_LOG"

echo "--- drivers ---"
# A driver reports only once every socket is open, so on a short hold this may be
# empty even though connections are established. Count live sockets as a fallback.
if grep -qha "opened=" "$OUT"/soak-drv-"$SRV"-"$HEAP"-*.log 2>/dev/null; then
  grep -ha "opened=" "$OUT"/soak-drv-"$SRV"-"$HEAP"-*.log | sed 's/^/  /'
  # Failure reasons, not just counts: a bare total cannot distinguish a refused
  # connection from an exhausted port range, and those mean different things.
  grep -ha "  *[0-9]* x " "$OUT"/soak-drv-"$SRV"-"$HEAP"-*.log 2>/dev/null \
    | sort | uniq -c | head -5 | sed 's/^/  /'
else
  echo "  (still opening — increase hold-secs for a completed driver report)"
fi
echo "  established sockets: $(netstat -an 2>/dev/null | grep -c "\.$PORT.*ESTABLISHED")"

echo "--- server ---"
PEAK=$(grep -ao "contexts=[0-9]*" "$SRV_LOG" | sed 's/contexts=//' | sort -n | tail -1)
THR=$(grep -ao "threads=[0-9]*" "$SRV_LOG" | sed 's/threads=//' | sort -n | tail -1)
PTHREAD=$(grep -ac "pthread_create failed" "$SRV_LOG")
OOM=$(grep -ac "OutOfMemoryError" "$SRV_LOG")
echo "  peak_contexts=${PEAK:-0} max_threads=${THR:-?} pthread_failures=$PTHREAD oom=$OOM"
echo "  final: $(grep -a STAT "$SRV_LOG" | tail -1)"

# A plateau is only meaningful if the last few samples agree; a still-climbing
# number means the hold window was too short to have measured anything.
echo "--- last 5 samples (should be flat) ---"
grep -a STAT "$SRV_LOG" | tail -5 | sed 's/^/  /'
