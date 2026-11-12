#!/usr/bin/env bash
# Live-lecture benchmark demo for the fork-join examples.
#
#   ./demo.sh SumThread.java     time vs number of threads
#   ./demo.sh ArraySum.java      time vs array size; time vs sequential cutoff
#   ./demo.sh ArraySum2.java     time vs size; vs cutoff; vs pool parallelism
#   ./demo.sh all                overlay of every mode vs array size (from CSVs only)
#
# Options:  --quick (default, < 45 s per program)   --full (publication figures)
#           --no-show (don't open the PNGs)          --plot-only (re-plot from CSV)
#
# Results append to results/<Program>.csv; figures go to figures/<Program>_*.png.
set -euo pipefail
cd "$(dirname "$0")"

for tool in java javac python3; do
    command -v "$tool" >/dev/null || { echo "demo.sh: '$tool' not found on PATH" >&2; exit 1; }
done
python3 -c 'import matplotlib' 2>/dev/null || {
    echo "demo.sh: matplotlib missing - install with: pip install matplotlib" >&2; exit 1; }

exec python3 -u demo.py "$@"
