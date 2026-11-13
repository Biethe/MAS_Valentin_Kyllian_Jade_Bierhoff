#!/usr/bin/env python3
"""Turns ./bench runs into CSV tables for the report's pgfplots figures.

usage: python3 report/extract.py label=RUNID [label=RUNID ...]
       (labels become column names, keep them alphanumeric: wfNomem, agMem, ...)
Writes report/data/{curves,dims,kinds,latency,debate,routing}.csv. Standard library only.
"""
import json, sys, statistics
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNS, OUT = ROOT / "benchmark" / "runs", ROOT / "report" / "data"
DIMS = ["task", "factuality", "tone", "relevance", "concise", "injection", "reliability"]
DEBATE = ["steps", "toolCalls", "proposals", "attacks", "concessions", "defences", "deadlines", "accepts",
          "normViolations", "lessonsRecalled", "lessonsLearned", "semanticSearches"]


def dims(r):
    """Same rule as Bench.scorecard: invalid fails task, factuality and reliability."""
    if r["outcome"] == "invalid":
        return {"task": False, "factuality": False, "reliability": False}
    return {**r["dims"], "reliability": True}


def main(pairs):
    OUT.mkdir(parents=True, exist_ok=True)
    runs = {}
    for p in pairs:
        label, run = p.split("=", 1)
        # "-" = configuration not run yet: an empty column, so the report still compiles
        runs[label] = (run, {"scenarios": [], "scorecard": {"p95Ms": float("nan"), "composite": float("nan")}} if run == "-"
                       else json.loads((RUNS / run / "results.json").read_text()))
    labels = list(runs)
    curves, dimrate, kinds, lat, debate, routing = {}, {}, {}, {}, {}, {}
    for label, (run, res) in runs.items():
        by_trial, by_dim, by_kind, ms_trial = defaultdict(list), defaultdict(list), defaultdict(list), defaultdict(list)
        deb, route = defaultdict(lambda: defaultdict(list)), defaultdict(int)
        for r in res["scenarios"]:
            d = dims(r)
            score = sum(d.values()) / len(d)
            t = r.get("repeat", 1)
            by_trial[t].append(score)
            by_kind[r["kind"]].append(score)
            ms_trial[t].append(r["ms"] / 1000)
            for k, v in d.items():
                by_dim[k].append(1.0 if v else 0.0)
            side = RUNS / run / "traces" / (r["trace"] + ".jsonl.agentic.json")
            if side.exists():
                s = json.loads(side.read_text())
                for k in DEBATE:
                    deb[t][k].append(s.get(k, 0))
                route[s.get("specialist", "?")] += 1
        if not res["scenarios"]:
            curves[label], dimrate[label], kinds[label], lat[label] = {}, {}, {}, {"p95": float("nan")}
            continue
        curves[label] = {t: statistics.mean(v) for t, v in sorted(by_trial.items())}
        dimrate[label] = {k: statistics.mean(v) for k, v in by_dim.items()}
        kinds[label] = {k: statistics.mean(v) for k, v in by_kind.items()}
        lat[label] = {t: statistics.mean(v) for t, v in sorted(ms_trial.items())}
        lat[label]["p95"] = res["scorecard"]["p95Ms"] / 1000
        if deb:
            debate[label] = {t: {k: statistics.mean(v) for k, v in m.items()} for t, m in sorted(deb.items())}
            routing[label] = dict(route)

    trials = sorted({t for c in curves.values() for t in c})
    write("curves.csv", ["trial"] + labels, [[t] + [curves[l].get(t, "nan") for l in labels] for t in trials])
    write("latency.csv", ["trial"] + labels, [[t] + [lat[l].get(t, "nan") for l in labels] for t in trials])
    write("dims.csv", ["dim", "x"] + labels, [[d, i] + [dimrate[l].get(d, "nan") for l in labels] for i, d in enumerate(DIMS)])
    allk = sorted({k for v in kinds.values() for k in v})
    write("kinds.csv", ["kind", "x"] + labels, [[k, i] + [kinds[l].get(k, "nan") for l in labels] for i, k in enumerate(allk)])
    rows = []
    for l, per in debate.items():
        for t, m in per.items():
            rows.append([l, t] + [round(m.get(k, 0), 3) for k in DEBATE])
    if not any(r[0] == "agMem" for r in rows):
        rows.append(["agMem", 1] + ["nan"] * len(DEBATE))
    write("debate.csv", ["label", "trial"] + DEBATE, rows)
    specs = sorted({s for r in routing.values() for s in r})
    write("routing.csv", ["specialist", "x"] + list(routing), [[s, i] + [routing[l].get(s, 0) for l in routing] for i, s in enumerate(specs)])
    for l in labels:
        sc = runs[l][1]["scorecard"]
        print(f"{l:10} composite {sc['composite']}  curve {[round(v, 3) for v in curves[l].values()]}  p95 {lat[l]['p95']:.1f}s")


def write(name, header, rows):
    with open(OUT / name, "w") as f:
        f.write(" ".join(header) + "\n")
        for r in rows:
            f.write(" ".join(f"{x:.4f}" if isinstance(x, float) else str(x) for x in r) + "\n")


if __name__ == "__main__":
    main(sys.argv[1:])
