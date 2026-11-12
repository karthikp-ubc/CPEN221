#!/usr/bin/env python3
"""Benchmark driver behind demo.sh. Standard library + matplotlib only.

Every data point is one fresh JVM that does `warmup` discarded runs, then
`trials` timed runs, and prints the median (see each program's main()).
Rows are printed as they finish, appended to results/<Program>.csv, and
plots are drawn from the CSV (latest run of each experiment).
"""
import argparse
import csv
import datetime as dt
import os
import re
import signal
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
BUILD, RESULTS, FIGURES = HERE / "build", HERE / "results", HERE / "figures"
NPROC = os.cpu_count() or 1
PROGRAMS = ["SumThread", "ArraySum", "ArraySum2"]

# SumThread.sum() computes i * len / numThreads in int arithmetic, which overflows
# once (numThreads-1) * len > 2^31-1. 8M is the largest round size safe for 256.
SUMTHREAD_N = 8_000_000

PRESETS = {
    "quick": dict(
        warmup=5, trials=11, timeout=5,
        threads=[1, 2, 4, 8, 16, 32, 64, 128, 256],
        sizes=[10**5, 10**6, 10**7],
        as_cutoff_n=10**6,              # ArraySum: thread storms make 1e7 too slow live
        fj_cutoff_n=10**7,
        cutoffs=[10**6, 10**5, 10**4, 10**3, 100],
        par_n=10**7,
        parallelism=list(range(1, NPROC + 1)),
        dpi=150,
    ),
    "full": dict(
        warmup=5, trials=15, timeout=300,
        threads=[1, 2, 4, 8, 16, 32, 64, 128, 256],
        sizes=[10**5, 3 * 10**5, 10**6, 3 * 10**6, 10**7, 3 * 10**7, 10**8],
        as_cutoff_n=10**7,
        fj_cutoff_n=10**8,
        cutoffs=[10**6, 3 * 10**5, 10**5, 3 * 10**4, 10**4, 3000, 1000, 300, 100],
        par_n=10**8,
        parallelism=list(range(1, NPROC + 1)),
        dpi=300,
    ),
}

FIELDS = ["run_id", "timestamp", "preset", "experiment", "mode", "n", "threads",
          "cutoff", "parallelism", "warmup", "trials", "median_ms", "status", "note"]

# ---------------------------------------------------------------- formatting

def si(x):
    x = float(x)
    for div, suf in ((1e9, "G"), (1e6, "M"), (1e3, "k")):
        if x >= div:
            v = x / div
            return f"{v:.0f}{suf}" if v == int(v) else f"{v:.1f}{suf}"
    return f"{x:.0f}"


def ms(x):
    return "" if x is None else (f"{x:.3f}" if x < 10 else f"{x:.1f}")


class Table:
    """Prints a fixed-width table one row at a time (never buffered)."""

    def __init__(self, title, cols):
        self.cols = cols  # list of (header, width)
        print(f"\n== {title}")
        print("  " + " ".join(h.rjust(w) for h, w in cols))
        print("  " + " ".join("-" * w for _, w in cols))

    def start(self, *cells):
        # parameter cells first, so the audience sees what is running
        print("  " + " ".join(str(c).rjust(w) for c, (_, w) in zip(cells, self.cols)),
              end=" ", flush=True)

    def finish(self, *cells):
        rest = self.cols[len(self.cols) - len(cells):]
        print(" ".join(str(c).rjust(w) for c, (_, w) in zip(cells, rest)), flush=True)

# ------------------------------------------------------------------ compiling

def compile_program(prog):
    """Each program gets its own output dir: SumThread.java and ArraySum.java
    both declare class SumThread, so they cannot share a directory."""
    src, out = HERE / f"{prog}.java", BUILD / prog
    cls = out / f"{prog}.class"
    if cls.exists() and cls.stat().st_mtime >= src.stat().st_mtime:
        print(f"[build] {prog}: up to date ({out.relative_to(HERE)}/)")
        return
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.class"):
        old.unlink()
    print(f"[build] {prog}: javac {src.name} -> {out.relative_to(HERE)}/", flush=True)
    # empty -sourcepath: never pull in a sibling file's SumThread
    r = subprocess.run(["javac", "-nowarn", "-sourcepath", "", "-d", str(out), str(src)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"compile failed for {src.name}:\n{r.stderr}")

# -------------------------------------------------------------------- running

def run_java(prog, args, timeout, jvm_flags=()):
    """Returns (status, stdout, note). Kills the whole JVM on timeout."""
    cmd = ["java", *jvm_flags, "-cp", str(BUILD / prog), prog, *map(str, args)]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                         text=True, start_new_session=True)
    try:
        out, err = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        os.killpg(p.pid, signal.SIGKILL)
        p.communicate()
        return "timeout", "", f"no result within {timeout}s"
    # threads that die (e.g. "unable to create native thread") only print to stderr
    deaths = re.findall(r'Exception in thread "[^"]*" (\S+?)(?::|\s|$)(.*)', err)
    note = ""
    if deaths:
        kinds = sorted({d[0].rsplit('.', 1)[-1] for d in deaths})
        note = f"{len(deaths)} thread(s) died: {', '.join(kinds)}"
    if "WRONG ANSWER" in out:
        return "wrong", out, note or "wrong sum"
    if p.returncode != 0:
        last = (err.strip().splitlines() or ["?"])[-1][:80]
        return "error", out, note or f"exit {p.returncode}: {last}"
    return "ok", out, note


def grab(pattern, text):
    m = re.search(pattern, text)
    return float(m.group(1)) if m else None


class Recorder:
    def __init__(self, prog, preset, cfg):
        self.path = RESULTS / f"{prog}.csv"
        self.run_id = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.preset, self.cfg = preset, cfg
        RESULTS.mkdir(exist_ok=True)
        if not self.path.exists():
            with self.path.open("w", newline="") as f:
                csv.writer(f).writerow(FIELDS)

    def add(self, experiment, mode, median, status, note="", **params):
        row = dict(run_id=self.run_id, timestamp=dt.datetime.now().isoformat(timespec="seconds"),
                   preset=self.preset, experiment=experiment, mode=mode,
                   warmup=self.cfg["warmup"], trials=self.cfg["trials"],
                   median_ms="" if median is None else f"{median:.6f}",
                   status=status, note=note)
        row.update({k: params.get(k, "") for k in ("n", "threads", "cutoff", "parallelism")})
        with self.path.open("a", newline="") as f:  # append + close per row: survives Ctrl-C
            csv.DictWriter(f, FIELDS).writerow(row)


def status_cell(status, note):
    return {"timeout": "TIMED OUT", "wrong": "WRONG SUM", "error": "ERROR",
            "skipped": "skipped"}.get(status, "") + (f"  ({note})" if note else "")

# ---------------------------------------------------------------- experiments

def bench_sumthread(rec, cfg, show):
    n, w, t = SUMTHREAD_N, cfg["warmup"], cfg["trials"]
    tab = Table(f"SumThread: time vs threads, n = {si(n)} ints, fixed split (nproc = {NPROC})",
                [("threads", 8), ("median ms", 10), ("vs 1 thread", 11), ("", 0)])
    base = None
    for k in cfg["threads"]:
        tab.start(k)
        status, out, note = run_java("SumThread", [n, k, t, w], cfg["timeout"])
        med = grab(r"computed in ([\d.Ee+-]+)ms", out) if status == "ok" else None
        if k == 1 and med:
            base = med
        speed = f"{base / med:.2f}x" if (base and med) else ""
        tab.finish(ms(med), speed, status_cell(status, note))
        rec.add("threads", "SumThread", med, status, note, n=n, threads=k)
    plot_program("SumThread", cfg, show)


def bench_arraysum(rec, cfg, show):
    w, t, to = cfg["warmup"], cfg["trials"], cfg["timeout"]
    modes = [("same-thread", "false"), ("separate-threads", "true")]

    # (a) time vs array size at the program's default cutoff
    tab = Table("ArraySum (a): time vs array size, cutoff = 10k",
                [("n", 6), ("mode", 17), ("median ms", 10), ("vs seq", 7), ("", 0)])
    for n in cfg["sizes"]:
        seq = None
        for mode, flag in modes:
            tab.start(si(n), mode)
            status, out, note = run_java("ArraySum", [n, t, flag, 10000, w], to)
            par = grab(r"Median parallel Duration \(ms\) = ([\d.Ee+-]+)", out)
            if mode == "same-thread":
                seq = grab(r"Median sequential Duration \(ms\) = ([\d.Ee+-]+)", out)
            tab.finish(ms(par), f"{seq / par:.2f}x" if seq and par else "",
                       status_cell(status, note))
            rec.add("size", mode, par if status == "ok" else None, status, note,
                    n=n, cutoff=10000)
        if seq is not None:  # sequential comes from the same JVM as same-thread
            tab.start(si(n), "sequential")
            tab.finish(ms(seq), "1.00x", "")
            rec.add("size", "sequential", seq, "ok", n=n)
    plot_program("ArraySum", cfg, show, only="size")

    # (b) time vs sequential cutoff at one large array, largest cutoff first
    n = cfg["as_cutoff_n"]
    tab = Table(f"ArraySum (b): time vs sequential cutoff, n = {si(n)}  "
                f"(timeout {to}s per point)",
                [("cutoff", 7), ("mode", 17), ("median ms", 10), ("leaf tasks", 10), ("", 0)])
    for mode, flag in modes:
        dead = False
        for c in cfg["cutoffs"]:
            tab.start(si(c), mode)
            if dead:
                status, out, note = "skipped", "", "a larger cutoff already timed out"
            else:
                status, out, note = run_java("ArraySum", [n, t, flag, c, w], to)
                dead = status == "timeout"
            par = grab(r"Median parallel Duration \(ms\) = ([\d.Ee+-]+)", out)
            tab.finish(ms(par), si(leaves(n, c)), status_cell(status, note))
            rec.add("cutoff", mode, par if status == "ok" else None, status, note,
                    n=n, cutoff=c)
    plot_program("ArraySum", cfg, show, only="cutoff")


def leaves(n, cutoff):
    k = 1
    while n / k >= cutoff:  # run() splits while (hi - lo) >= cutoff
        k *= 2
    return k


def bench_arraysum2(rec, cfg, show):
    w, t, to = cfg["warmup"], cfg["trials"], cfg["timeout"]
    pat_p = r"Median parallel Duration \(ms\) = ([\d.Ee+-]+)"
    pat_s = r"Median sequential Duration \(ms\) = ([\d.Ee+-]+)"
    cols = lambda first: [(first, 7), ("seq ms", 10), ("fork-join ms", 12), ("speedup", 7), ("", 0)]

    def point(table_cells, args, flags=()):
        tab.start(*table_cells)
        status, out, note = run_java("ArraySum2", args, to, flags)
        s, p = grab(pat_s, out), grab(pat_p, out)
        tab.finish(ms(s), ms(p), f"{s / p:.2f}x" if s and p else "", status_cell(status, note))
        return status, note, s, p

    tab = Table(f"ArraySum2 (a): time vs array size, cutoff = 10k, "
                f"common pool parallelism = {NPROC - 1}", cols("n"))
    for n in cfg["sizes"]:
        status, note, s, p = point([si(n)], [n, t, 10000, w])
        rec.add("size", "sequential", s, "ok" if s else status, note, n=n)
        rec.add("size", "fork-join", p if status == "ok" else None, status, note, n=n, cutoff=10000)
    plot_program("ArraySum2", cfg, show, only="size")

    n = cfg["fj_cutoff_n"]
    tab = Table(f"ArraySum2 (b): time vs sequential cutoff, n = {si(n)}", cols("cutoff"))
    for c in cfg["cutoffs"]:
        status, note, s, p = point([si(c)], [n, t, c, w])
        rec.add("cutoff", "sequential", s, "ok" if s else status, note, n=n)
        rec.add("cutoff", "fork-join", p if status == "ok" else None, status, note, n=n, cutoff=c)
    plot_program("ArraySum2", cfg, show, only="cutoff")

    n = cfg["par_n"]
    tab = Table(f"ArraySum2 (c): time vs pool parallelism, n = {si(n)}, cutoff = 10k "
                f"(nproc = {NPROC})", cols("par."))
    for k in cfg["parallelism"]:
        status, note, s, p = point(
            [k], [n, t, 10000, w],
            [f"-Djava.util.concurrent.ForkJoinPool.common.parallelism={k}"])
        rec.add("parallelism", "sequential", s, "ok" if s else status, note, n=n, parallelism=k)
        rec.add("parallelism", "fork-join", p if status == "ok" else None, status, note,
                n=n, cutoff=10000, parallelism=k)
    plot_program("ArraySum2", cfg, show, only="parallelism")

# ------------------------------------------------------------------- plotting

# Validated categorical palette (fixed order), light surface.
C = {"blue": "#2a78d6", "orange": "#eb6834", "aqua": "#1baf7a",
     "yellow": "#eda100", "magenta": "#e87ba4", "violet": "#6250d6"}
INK, INK2, GRID = "#0b0b0b", "#52514e", "#e4e3df"
STYLE = {  # mode -> (color, marker, label). Color follows the mode in every figure.
    "sequential":       (C["blue"], "o", "Sequential"),
    "same-thread":      (C["orange"], "s", "Threads: right.run()"),
    "separate-threads": (C["aqua"], "^", "Threads: right.start()"),
    "fork-join":        (C["violet"], "D", "Fork-join pool"),
    "SumThread":        (C["magenta"], "o", "SumThread (fixed split)"),
}


def mpl():
    import warnings
    warnings.filterwarnings("ignore", message="Unable to import Axes3D")
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams.update({
        "font.size": 11, "axes.titlesize": 13, "axes.titleweight": "bold",
        "axes.labelcolor": INK2, "axes.edgecolor": GRID, "axes.linewidth": 1,
        "xtick.color": INK2, "ytick.color": INK2, "axes.grid": True,
        "grid.color": GRID, "grid.linewidth": 0.8, "axes.axisbelow": True,
        "axes.spines.top": False, "axes.spines.right": False,
        "figure.facecolor": "white", "axes.facecolor": "white",
        "legend.frameon": False, "lines.linewidth": 2, "lines.markersize": 8,
    })
    return plt


def read_csv(prog):
    path = RESULTS / f"{prog}.csv"
    if not path.exists():
        return None
    with path.open(newline="") as f:
        return list(csv.DictReader(f))


def latest(rows, experiment):
    """Rows of the most recent run that contains this experiment."""
    rows = [r for r in rows if r["experiment"] == experiment]
    if not rows:
        return []
    rid = max(r["run_id"] for r in rows)
    return [r for r in rows if r["run_id"] == rid]


def series(rows, mode, xkey):
    pts = sorted((float(r[xkey]), r) for r in rows if r["mode"] == mode)
    ok = [(x, float(r["median_ms"])) for x, r in pts if r["status"] == "ok" and r["median_ms"]]
    bad = [(x, r["status"]) for x, r in pts if r["status"] in ("timeout", "skipped", "wrong", "error")]
    return ok, bad


def draw(ax, rows, modes, xkey):
    """Line per mode; failed points shown as an X pinned to the top edge."""
    import matplotlib.transforms as mt
    top = mt.blended_transform_factory(ax.transData, ax.transAxes)
    drew_fail = False
    for i, mode in enumerate(modes):
        ok, bad = series(rows, mode, xkey)
        color, marker, label = STYLE[mode]
        if ok:
            xs, ys = zip(*ok)
            ax.plot(xs, ys, color=color, marker=marker, label=label,
                    markeredgecolor="white", markeredgewidth=1.2)
        for x, st in bad:
            if st in ("timeout", "wrong", "error"):
                y = 0.96 - 0.07 * i
                ax.plot([x], [y], transform=top, marker="X", color=color, markersize=10,
                        markeredgecolor="white", clip_on=False, linestyle="none")
                ax.annotate("timed out" if st == "timeout" else st, (x, y), xycoords=top,
                            xytext=(8, 0), textcoords="offset points", va="center",
                            fontsize=9, color=INK2)
                drew_fail = True
    return drew_fail


def plain_log(*axes):
    from matplotlib.ticker import FuncFormatter, LogLocator, NullFormatter
    fmt = FuncFormatter(lambda v, _: f"{v:g}" if v < 1e4 else si(v))
    for a in axes:
        lo, hi = a.get_view_interval()
        span = hi / lo if lo > 0 else 1e9  # under ~1.5 decades, label more steps
        a.set_major_locator(LogLocator(base=10, subs=(1, 2, 5) if span > 30 else range(1, 10)))
        a.set_major_formatter(fmt)
        a.set_minor_formatter(NullFormatter())


def footer(fig, rows, extra=""):
    r = rows[0]
    fig.text(0.01, -0.01,
             f"Median of {r['trials']} timed runs after {r['warmup']} discarded warm-up runs"
             f" · one JVM per point · {NPROC} logical CPUs · run {r['run_id']}"
             f" ({r['preset']}){extra}", fontsize=8, color=INK2, ha="left", va="top")


def save(fig, name, cfg, plt):
    FIGURES.mkdir(exist_ok=True)
    path = FIGURES / f"{name}.png"
    fig.savefig(path, dpi=cfg["dpi"], bbox_inches="tight")
    if cfg["dpi"] >= 300:
        fig.savefig(path.with_suffix(".pdf"), bbox_inches="tight")
    plt.close(fig)
    print(f"[plot] {path.relative_to(HERE)}", flush=True)
    return path


def open_file(path):
    if not (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
        print("[plot] no display; not opening (use --no-show to silence)")
        return
    subprocess.Popen(["xdg-open", str(path)], stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, start_new_session=True)


def timeout_note(failed):
    return "  ·  ✖ at top = timed out (no result)" if failed else ""


def plot_program(prog, cfg, show, only=None):
    plt = mpl()
    rows = read_csv(prog) or []
    out = []
    want = lambda e: only in (None, e)

    if prog == "SumThread" and want("threads"):
        rs = latest(rows, "threads")
        if rs:
            fig, ax = plt.subplots(figsize=(8, 4.8))
            failed = draw(ax, rs, ["SumThread"], "threads")
            ax.set_xscale("log", base=2)
            ax.set_yscale("log")
            plain_log(ax.yaxis)
            ks = sorted({int(r["threads"]) for r in rs})
            ax.set_xticks(ks, [str(k) for k in ks])
            ax.axvline(NPROC, color=INK2, linestyle="--", linewidth=1)
            ax.text(NPROC * 1.06, 0.95, f"{NPROC} logical CPUs", transform=ax.get_xaxis_transform(),
                    color=INK2, fontsize=9, va="top")
            ax.set_xlabel("number of threads")
            ax.set_ylabel("median time (ms, log scale)")
            ax.set_title(f"SumThread: summing {si(rs[0]['n'])} ints with k threads")
            footer(fig, rs, timeout_note(failed))
            out.append(save(fig, "SumThread_threads", cfg, plt))

    if prog in ("ArraySum", "ArraySum2") and want("size"):
        rs = latest(rows, "size")
        if rs:
            modes = (["sequential", "same-thread", "separate-threads"] if prog == "ArraySum"
                     else ["sequential", "fork-join"])
            fig, ax = plt.subplots(figsize=(8, 4.8))
            failed = draw(ax, rs, modes, "n")
            ax.set_xscale("log"); ax.set_yscale("log"); plain_log(ax.xaxis, ax.yaxis)
            ax.set_xlabel("array length (ints)")
            ax.set_ylabel("median time (ms, log scale)")
            ax.set_title(f"{prog}: time vs array size (cutoff 10k)")
            ax.legend(loc="upper left")
            footer(fig, rs, timeout_note(failed))
            out.append(save(fig, f"{prog}_size", cfg, plt))

    if prog in ("ArraySum", "ArraySum2") and want("cutoff"):
        rs = latest(rows, "cutoff")
        if rs:
            modes = ["same-thread", "separate-threads"] if prog == "ArraySum" else ["fork-join"]
            fig, ax = plt.subplots(figsize=(8, 4.8))
            failed = draw(ax, rs, modes, "cutoff")
            seq = [float(r["median_ms"]) for r in rs if r["mode"] == "sequential" and r["median_ms"]]
            if prog == "ArraySum2" and seq:
                s = sorted(seq)[len(seq) // 2]
                ax.axhline(s, color=STYLE["sequential"][0], linestyle="--", linewidth=1.5,
                           label=f"Sequential ({ms(s)} ms)")
            ax.set_xscale("log"); ax.set_yscale("log"); plain_log(ax.xaxis, ax.yaxis)
            ax.set_xlabel("sequential cutoff (elements per leaf)")
            ax.set_ylabel("median time (ms, log scale)")
            ax.set_title(f"{prog}: time vs sequential cutoff, n = {si(rs[0]['n'])}")
            ax.legend(loc="upper right")
            footer(fig, rs, timeout_note(failed))
            out.append(save(fig, f"{prog}_cutoff", cfg, plt))

    if prog == "ArraySum2" and want("parallelism"):
        rs = latest(rows, "parallelism")
        if rs:
            fig, ax = plt.subplots(figsize=(8, 4.8))
            failed = draw(ax, rs, ["fork-join"], "parallelism")
            seq = sorted(float(r["median_ms"]) for r in rs
                         if r["mode"] == "sequential" and r["median_ms"])
            if seq:
                s = seq[len(seq) // 2]
                ax.axhline(s, color=STYLE["sequential"][0], linestyle="--", linewidth=1.5,
                           label=f"Sequential ({ms(s)} ms)")
            ks = sorted({int(r["parallelism"]) for r in rs})
            ax.set_xticks(ks)
            ax.set_ylim(bottom=0)
            ax.set_xlabel("ForkJoinPool.common.parallelism")
            ax.set_ylabel("median time (ms)")
            ax.set_title(f"ArraySum2: time vs pool parallelism, n = {si(rs[0]['n'])}")
            ax.legend(loc="upper right")
            footer(fig, rs, timeout_note(failed))
            out.append(save(fig, "ArraySum2_parallelism", cfg, plt))

    if show:
        for p in out:
            open_file(p)
    return out


def plot_all(cfg, show):
    data = {p: read_csv(p) for p in PROGRAMS}
    missing = [f"results/{p}.csv (run ./demo.sh {p}.java)" for p, d in data.items() if not d]
    if missing:
        print("demo: cannot build the overlay; missing:\n  " + "\n  ".join(missing))
        sys.exit(1)
    st = latest(data["SumThread"], "threads")
    a1 = latest(data["ArraySum"], "size")
    a2 = latest(data["ArraySum2"], "size")
    for name, rs in (("SumThread.csv has no 'threads'", st), ("ArraySum.csv has no 'size'", a1),
                     ("ArraySum2.csv has no 'size'", a2)):
        if not rs:
            print(f"demo: cannot build the overlay; {name} experiment rows")
            sys.exit(1)

    plt = mpl()
    print("\n== Overlay: every mode vs array size (from results/*.csv, nothing re-run)")
    fig, ax = plt.subplots(figsize=(9, 5.4))
    failed = draw(ax, a1, ["sequential", "same-thread", "separate-threads"], "n")
    failed |= draw(ax, a2, ["fork-join"], "n")
    best = min((r for r in st if r["status"] == "ok" and r["median_ms"]),
               key=lambda r: float(r["median_ms"]), default=None)
    if best:
        color, marker, label = STYLE["SumThread"]
        ax.plot([float(best["n"])], [float(best["median_ms"])], marker=marker, color=color,
                markersize=11, markeredgecolor="white", linestyle="none",
                label=f"{label}, best k = {best['threads']}")
    ax.set_xscale("log"); ax.set_yscale("log"); plain_log(ax.xaxis, ax.yaxis)
    ax.set_xlabel("array length (ints)")
    ax.set_ylabel("median time (ms, log scale)")
    ax.set_title("Summing an int array: every approach, same machine")
    ax.legend(loc="upper left")
    runs = ", ".join(f"{p} {rs[0]['run_id']} ({rs[0]['preset']})"
                     for p, rs in (("SumThread", st), ("ArraySum", a1), ("ArraySum2", a2)))
    fig.text(0.01, -0.01, f"Medians from results/*.csv · {runs}{timeout_note(failed)}",
             fontsize=8, color=INK2, ha="left", va="top")
    # Table of what went into the overlay
    print(f"  {'n':>6} {'mode':>24} {'median ms':>10}  source")
    print(f"  {'-'*6} {'-'*24} {'-'*10}  ------")
    for src, rs, modes in (("ArraySum", a1, ["sequential", "same-thread", "separate-threads"]),
                           ("ArraySum2", a2, ["fork-join"])):
        for r in sorted(rs, key=lambda r: (r["mode"], float(r["n"]))):
            if r["mode"] in modes:
                v = ms(float(r["median_ms"])) if r["median_ms"] else r["status"].upper()
                print(f"  {si(r['n']):>6} {STYLE[r['mode']][2]:>24} {v:>10}  {src}")
    if best:
        print(f"  {si(best['n']):>6} {'SumThread k=' + best['threads']:>24} "
              f"{ms(float(best['median_ms'])):>10}  SumThread")
    p = save(fig, "all_vs_size", cfg, plt)
    if show:
        open_file(p)

# ----------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(prog="demo.sh", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("program", choices=[f"{p}.java" for p in PROGRAMS] + PROGRAMS + ["all"])
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--quick", action="store_true", help="small sizes, few trials (default)")
    g.add_argument("--full", action="store_true", help="large sizes, many trials, 300 dpi + PDF")
    ap.add_argument("--no-show", action="store_true", help="save figures but don't open them")
    ap.add_argument("--plot-only", action="store_true", help="re-plot from results/ without running")
    a = ap.parse_args()

    preset = "full" if a.full else "quick"
    cfg, show = PRESETS[preset], not a.no_show
    prog = a.program.removesuffix(".java")
    t0 = time.monotonic()

    if prog == "all":
        plot_all(cfg, show)
        return
    if a.plot_only:
        if not read_csv(prog):
            sys.exit(f"demo: results/{prog}.csv not found; run ./demo.sh {prog}.java first")
        plot_program(prog, cfg, show)
        return

    jv = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0]
    print(f"=== {prog}.java  [{preset}]  {NPROC} logical CPUs  {jv}")
    print(f"=== Each point: {cfg['warmup']} warm-up runs (discarded), then median of "
          f"{cfg['trials']} timed runs; timeout {cfg['timeout']}s per point")
    compile_program(prog)
    rec = Recorder(prog, preset, cfg)
    print(f"[csv] appending to {rec.path.relative_to(HERE)} (run {rec.run_id})")
    {"SumThread": bench_sumthread, "ArraySum": bench_arraysum,
     "ArraySum2": bench_arraysum2}[prog](rec, cfg, show)
    print(f"\n=== done in {time.monotonic() - t0:.1f}s")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\ninterrupted; rows so far are already in results/")
        sys.exit(130)
