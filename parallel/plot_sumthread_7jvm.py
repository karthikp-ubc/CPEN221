#!/usr/bin/env python3
"""SumThread time vs threads across several fresh JVMs per point.

A single demo.sh run of SumThread is noisy (the 1-thread point is bimodal),
so this measures each thread count in several fresh JVMs and plots the median
with a min-max band.

    python3 plot_sumthread_7jvm.py             # re-plot from results/SumThread_7jvm.txt
    python3 plot_sumthread_7jvm.py --collect   # re-measure first (~1 min), then plot

Data format (results/SumThread_7jvm.txt): one line per thread count,
"k t1 t2 ... tJ", each t the median-of-11 time (ms) reported by one JVM.
Requires build/SumThread/ (run ./demo.sh SumThread.java once to compile).
"""
import argparse
import datetime as dt
import re
import statistics as st
import subprocess
from pathlib import Path

import demo

HERE = Path(__file__).resolve().parent
DATA = HERE / "results" / "SumThread_7jvm.txt"
FIG = HERE / "figures" / "SumThread_threads_7jvm.png"
N, TRIALS, WARMUP = 10_000_000, 11, 5
THREADS = [1, 2, 4, 8, 16, 32, 64, 128, 256]


def collect(jvms):
    lines = []
    for k in THREADS:
        times = []
        for _ in range(jvms):
            out = subprocess.run(
                ["java", "-cp", str(HERE / "build" / "SumThread"), "SumThread",
                 str(N), str(k), str(TRIALS), str(WARMUP)],
                capture_output=True, text=True, check=True).stdout
            times.append(re.search(r" in ([0-9.]+)ms", out).group(1))
        lines.append(f"{k} {' '.join(times)}")
        print(lines[-1], flush=True)
    DATA.write_text("\n".join(lines) + "\n")


def plot():
    plt = demo.mpl()
    rows = [l.split() for l in DATA.read_text().splitlines() if l.strip()]
    ks = [int(r[0]) for r in rows]
    vals = [sorted(map(float, r[1:])) for r in rows]
    jvms = len(vals[0])
    med = [st.median(v) for v in vals]
    color, marker, _ = demo.STYLE["SumThread"]

    fig, ax = plt.subplots(figsize=(8, 4.8))
    ax.fill_between(ks, [v[0] for v in vals], [v[-1] for v in vals], color=color,
                    alpha=0.18, linewidth=0, label="min–max across JVMs")
    ax.plot(ks, med, color=color, marker=marker, label=f"median across {jvms} JVMs",
            markeredgecolor="white", markeredgewidth=1.2)
    ax.set_xscale("log", base=2)
    ax.set_yscale("log")
    demo.plain_log(ax.yaxis)
    ax.set_xticks(ks, [str(k) for k in ks])
    ax.axvline(demo.NPROC, color=demo.INK2, linestyle="--", linewidth=1)
    ax.text(demo.NPROC * 1.06, 0.95, f"{demo.NPROC} logical CPUs",
            transform=ax.get_xaxis_transform(), color=demo.INK2, fontsize=9, va="top")
    ax.set_xlabel("number of threads")
    ax.set_ylabel("time (ms, log scale)")
    ax.set_title(f"SumThread: summing {demo.si(N)} ints with k threads ({jvms} fresh JVMs per point)")
    ax.legend(frameon=False)
    when = dt.date.fromtimestamp(DATA.stat().st_mtime).isoformat()
    fig.text(0.01, -0.01,
             f"Each JVM: median of {TRIALS} timed runs after {WARMUP} warm-up runs"
             f" · line = median of the {jvms} JVM medians · {demo.NPROC} logical CPUs · {when}",
             fontsize=8, color=demo.INK2, ha="left", va="top")
    FIG.parent.mkdir(exist_ok=True)
    fig.savefig(FIG, dpi=150, bbox_inches="tight")
    print(f"[plot] {FIG.relative_to(HERE)}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--collect", action="store_true", help="re-measure before plotting")
    ap.add_argument("--jvms", type=int, default=7, help="fresh JVMs per thread count")
    args = ap.parse_args()
    if args.collect:
        collect(args.jvms)
    plot()
