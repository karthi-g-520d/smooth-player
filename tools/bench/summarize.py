#!/usr/bin/env python3
"""Summarizes Step 8 benchmark logs (fi-bench v1 CSVs from the player's Bench button) against the Go/No-Go gates.

Usage: python3 tools/bench/summarize.py fi-bench-*.csv [--markdown out.md]

Each file is split into runs by mode (a run that switched modes gives one run per mode). Cadence and drops use only the
rows outside the stage-timing windows, because those windows wait for the GPU with glFinish. Stage timings use only
the rows inside them. Battery drain is a straight-line fit of battery % over the run's unplugged rows.
"""
import argparse
import csv
import statistics
import sys
from dataclasses import dataclass, field

SCHEMA = "# fi-bench v1"
STAGE_BAR_MS = 10.0          # Gate 1: interpolation under 10 ms per generated frame
SUSTAINED_S = 600            # Gate 1: sustained 10+ minutes
CADENCE_BAR = 99.0           # Gate 2
STUTTER_SECOND = 90.0        # a second under this cadence counts as a stutter
BATTERY_RATIO_BAR = 1.5      # Gate 4: at most 1.5x the drain of baseline playback
SEVERE_THERMAL = 3           # PowerManager.THERMAL_STATUS_SEVERE
MIN_BATTERY_FIT_S = 600      # under 10 minutes, a 1% battery step is too coarse to fit
THERMAL_NAMES = ["none", "light", "moderate", "severe", "critical", "emergency", "shutdown"]


@dataclass
class Run:
    path: str
    meta: dict
    mode: str
    rows: list = field(default_factory=list)


def read_runs(path):
    meta, lines = {}, []
    with open(path, newline="", encoding="utf-8") as f:
        first = f.readline().strip()
        if first != SCHEMA:
            sys.exit(f"{path}: expected '{SCHEMA}', got '{first}'")
        for line in f:
            if line.startswith("# "):
                key, _, value = line[2:].strip().partition("=")
                meta[key] = value
            else:
                lines.append(line)
    runs = []
    for row in csv.DictReader(lines):
        if not runs or runs[-1].mode != row["mode"]:
            runs.append(Run(path, meta, row["mode"]))
        runs[-1].rows.append(row)
    return runs


def num(row, key):
    value = row.get(key, "")
    return float(value) if value not in ("", None) else None


def pct(values, q):
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, min(len(ordered), -(-int(q * len(ordered) * 1000) // 1000)))
    return ordered[rank - 1]


def fmt(value, unit="", digits=2):
    return "n/a" if value is None else f"{value:.{digits}f}{unit}"


def summarize(run):
    rows = run.rows
    clean = [r for r in rows if r["probe"] == "0"]
    probed = [r for r in rows if r["probe"] == "1"]
    elapsed = [num(r, "elapsed_s") for r in rows]
    duration = (elapsed[-1] - elapsed[0]) if len(elapsed) > 1 else 0.0

    runs_total = sum(int(r["runs"]) for r in clean)
    exact_total = sum(int(r["exact_runs"]) for r in clean)
    cadence = 100.0 * exact_total / runs_total if runs_total else None
    per_second = [100.0 * int(r["exact_runs"]) / int(r["runs"]) for r in clean if int(r["runs"]) > 0]
    stutters = [num(r, "elapsed_s") for r in clean if int(r["runs"]) > 0
                and 100.0 * int(r["exact_runs"]) / int(r["runs"]) < STUTTER_SECOND]
    # Stutter seconds within 2 s of each other are one event. Periodic stutter: most gaps between events are within
    # 25% of the median gap (a hitch every N seconds), with at least five events.
    events = []
    for t in stutters:
        if not events or t - events[-1][-1] > 2.0:
            events.append([t])
        else:
            events[-1].append(t)
    starts = [e[0] for e in events]
    gaps = [b - a for a, b in zip(starts, starts[1:])]
    period = statistics.median(gaps) if gaps else None
    steady = sum(1 for g in gaps if period and abs(g - period) <= 0.25 * period)
    periodic = len(gaps) >= 5 and steady >= 0.6 * len(gaps)
    dropped = [num(r, "dropped_total") for r in rows]
    drops = (dropped[-1] - dropped[0]) if dropped and None not in (dropped[0], dropped[-1]) else None

    stages = {}
    for key in ("copy_ms", "me_ms", "warp_ms"):
        stages[key] = [v for v in (num(r, key) for r in probed) if v is not None]
    totals = [sum(v) for v in ((num(r, "copy_ms"), num(r, "me_ms"), num(r, "warp_ms")) for r in probed) if None not in v]
    # When a second has no complete triple, add up the stage p95s instead: an upper bound on the p95 total.
    total_p95 = pct(totals, 0.95) if len(totals) >= 10 else (
        sum(pct(stages[k], 0.95) for k in stages) if all(stages.values()) else None)
    windows = {}
    for r in probed:
        windows.setdefault(int(num(r, "elapsed_s") // 300), []).append(r)
    window_p95 = []
    for index in sorted(windows):
        w = windows[index]
        t = [sum(v) for v in ((num(r, "copy_ms"), num(r, "me_ms"), num(r, "warp_ms")) for r in w) if None not in v]
        window_p95.append((index * 5, pct(t, 0.95)))

    headroom = [v for v in (num(r, "thermal_headroom") for r in rows) if v is not None]
    status = [int(v) for v in (num(r, "thermal_status") for r in rows) if v is not None]
    first_moderate = next((num(r, "elapsed_s") for r in rows if (num(r, "thermal_status") or 0) >= 2), None)
    unplugged = [(num(r, "elapsed_s"), num(r, "battery_pct")) for r in rows
                 if r["charging"] != "1" and num(r, "battery_pct") is not None]
    drain = None
    if len(unplugged) > 1 and unplugged[-1][0] - unplugged[0][0] >= MIN_BATTERY_FIT_S:
        xs, ys = zip(*unplugged)
        mx, my = statistics.mean(xs), statistics.mean(ys)
        slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
        drain = -slope * 3600.0
    current = [v for v in (num(r, "battery_ma") for r in rows if r["charging"] != "1") if v is not None]
    temps = [v for v in (num(r, "battery_temp_c") for r in rows) if v is not None]
    brightness = [v for v in (num(r, "brightness") for r in rows) if v is not None]

    inb = {k: sum(int(r[k]) for r in rows) for k in ("inb_warped", "inb_pending", "inb_not_searched", "inb_low_conf", "inb_held")}
    inb_total = sum(inb.values())
    fallback_rows = {}
    for r in rows:
        if r["fallback"]:
            fallback_rows[r["fallback"]] = fallback_rows.get(r["fallback"], 0) + 1
    cuts = [num(r, "scene_cuts_total") for r in rows]

    return {
        "clip": run.meta.get("clip", ""), "size": run.meta.get("size", ""), "label": run.meta.get("label", ""),
        "device": run.meta.get("device", ""), "mode": run.mode, "file": run.path, "duration_s": duration,
        "cadence": cadence, "worst_second": min(per_second) if per_second else None, "stutter_seconds": len(stutters),
        "stutter_events": len(events), "stutter_period_s": period, "periodic_stutter": periodic, "drops": drops, "drift_ms": [num(r, "av_drift_ms") for r in rows if r["av_drift_ms"]],
        "stages": {k: (pct(v, 0.5), pct(v, 0.95), pct(v, 0.99), len(v)) for k, v in stages.items()},
        "total_p50": pct(totals, 0.5), "total_p95": total_p95, "total_p99": pct(totals, 0.99), "window_p95": window_p95,
        "headroom_max": max(headroom) if headroom else None, "status_max": max(status) if status else None,
        "first_moderate_s": first_moderate, "drain_pct_h": drain,
        "current_ma": statistics.mean(current) if current else None,
        "temp_max": max(temps) if temps else None,
        "brightness": statistics.median(brightness) if brightness else None,
        "inb": inb, "inb_total": inb_total, "fallback_rows": fallback_rows,
        "cuts": (cuts[-1] - cuts[0]) if cuts and None not in (cuts[0], cuts[-1]) else None,
    }


def gates(s, baseline):
    """Verdicts for one run. Gate 3 (viewers prefer it) is never decided from logs."""
    out = {}
    if s["mode"] == "MotionCompensated":
        if s["total_p95"] is None:
            out["1 under 10 ms"] = "no stage samples"
        else:
            ok = s["total_p95"] < STAGE_BAR_MS and all(p is None or p < STAGE_BAR_MS for _, p in s["window_p95"])
            long_enough = s["duration_s"] >= SUSTAINED_S
            out["1 under 10 ms"] = ("PASS" if ok else "FAIL") + ("" if long_enough else " (run under 10 min)")
    if s["cadence"] is not None:
        ok = s["cadence"] >= CADENCE_BAR and not s["periodic_stutter"]
        out["2 cadence"] = ("PASS" if ok else "FAIL") + f" ({s['cadence']:.1f}%" + (", periodic stutter)" if s["periodic_stutter"] else ")")
    thermal_ok = s["status_max"] is None or s["status_max"] < SEVERE_THERMAL
    if baseline and s is not baseline and baseline["drain_pct_h"] and s["drain_pct_h"] is not None:
        ratio = s["drain_pct_h"] / baseline["drain_pct_h"]
        out["4 heat/battery"] = ("PASS" if ratio <= BATTERY_RATIO_BAR and thermal_ok else "FAIL") + \
            f" ({ratio:.2f}x baseline drain, max thermal {THERMAL_NAMES[s['status_max']] if s['status_max'] is not None else 'n/a'})"
    elif s["status_max"] is not None:
        out["4 heat/battery"] = ("heat PASS" if thermal_ok else "heat FAIL") + \
            f" (max {THERMAL_NAMES[s['status_max']]}; battery needs a 30+ min run and a Duplicate baseline)"
    return out


def render(summaries):
    lines = ["# Step 8 benchmark summary", ""]
    baselines = {}
    for s in summaries:
        if s["mode"] == "Duplicate" and s["drain_pct_h"] is not None:
            baselines.setdefault(s["clip"], s)
    for s in summaries:
        lines.append(f"## {s['clip'] or '(clip?)'} {s['size']}, {s['mode']}, {s['duration_s'] / 60:.1f} min")
        lines.append(f"{s['label']} · {s['device']} · `{s['file']}`")
        lines.append("")
        lines.append("| Measure | Value |")
        lines.append("|---|---|")
        lines.append(f"| Cadence (outside timing windows) | {fmt(s['cadence'], '%', 1)}, worst second {fmt(s['worst_second'], '%', 0)}, "
                     f"{s['stutter_seconds']} seconds under {STUTTER_SECOND:.0f}% in {s['stutter_events']} events"
                     f"{', periodic about every %.0f s' % s['stutter_period_s'] if s['periodic_stutter'] else ''} |")
        lines.append(f"| Dropped frames | {fmt(s['drops'], '', 0)} |")
        if s["drift_ms"]:
            lines.append(f"| A/V drift | {min(s['drift_ms']):.0f} to {max(s['drift_ms']):.0f} ms |")
        for key, label in (("copy_ms", "Copy + luma"), ("me_ms", "Motion search"), ("warp_ms", "Warp draw")):
            p50, p95, p99, n = s["stages"][key]
            if n:
                lines.append(f"| {label} p50 / p95 / p99 | {fmt(p50)} / {fmt(p95)} / {fmt(p99)} ms ({n} samples) |")
        if s["total_p95"] is not None:
            lines.append(f"| Per generated frame p50 / p95 / p99 | {fmt(s['total_p50'])} / {fmt(s['total_p95'])} / {fmt(s['total_p99'])} ms |")
        if sum(p is not None for _, p in s["window_p95"]) > 1:
            lines.append("| Total p95 by timing window | " + ", ".join(f"{m} min: {fmt(p)}" for m, p in s["window_p95"]) + " |")
        status = THERMAL_NAMES[s["status_max"]] if s["status_max"] is not None else "n/a"
        moderate = "" if s["first_moderate_s"] is None else f", moderate from {s['first_moderate_s'] / 60:.1f} min"
        lines.append(f"| Thermal | max headroom {fmt(s['headroom_max'])}, max status {status}{moderate}, "
                     f"battery up to {fmt(s['temp_max'], ' °C', 1)} |")
        lines.append(f"| Battery | {fmt(s['drain_pct_h'], ' %/h', 1)}, mean current {fmt(s['current_ma'], ' mA', 0)}, brightness {fmt(s['brightness'], '', 0)}/255 |")
        if s["inb_total"]:
            share = {k: 100.0 * v / s["inb_total"] for k, v in s["inb"].items()}
            lines.append(f"| In-betweens | warped {share['inb_warped']:.0f}%, search pending {share['inb_pending']:.0f}%, "
                         f"not searched {share['inb_not_searched']:.0f}%, low confidence {share['inb_low_conf']:.0f}%, held {share['inb_held']:.0f}% |")
        if s["fallback_rows"]:
            lines.append("| Seconds per fallback state | " + ", ".join(f"{k} {v}" for k, v in s["fallback_rows"].items()) + " |")
        if s["cuts"] is not None and s["mode"] == "MotionCompensated":
            lines.append(f"| Scene cuts | {s['cuts']:.0f} |")
        for gate, verdict in gates(s, baselines.get(s["clip"])).items():
            lines.append(f"| Gate {gate} | {verdict} |")
        lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("logs", nargs="+")
    parser.add_argument("--markdown")
    args = parser.parse_args()
    summaries = [summarize(run) for path in args.logs for run in read_runs(path) if len(run.rows) > 1]
    text = render(summaries)
    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
