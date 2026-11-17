#!/usr/bin/env python3
"""Paired same-process-workload evidence; shared CI runners cannot promise production latency."""
import json
import math
from pathlib import Path

root = Path(__file__).resolve().parents[1]
results = json.loads((root / "benchmarks/build/results/jmh/results.json").read_text())
indexed = {(r["benchmark"].rsplit(".", 1)[-1], tuple(sorted(r.get("params", {}).items()))): r for r in results}
failures = []
pairs = [("generated", "jdbc"), ("generatedLedger", "jdbcLedger"),
         ("generatedPage", "jdbcPage"), ("generatedInsert", "jdbcInsert"), ("generatedBulk", "jdbcBulk")]
for generated, jdbc in pairs:
    variants = [(params, value) for (name, params), value in indexed.items() if name == generated]
    expected_rows = {"generatedPage": {"1", "100", "600"}, "generatedBulk": {"1", "32", "512"}}
    if generated in expected_rows:
        actual_rows = {dict(params).get("rows") for params, _ in variants}
        if actual_rows != expected_rows[generated]:
            failures.append(f"Missing or unexpected parameter variants for {generated}: {actual_rows}")
    elif len(variants) != 1 or variants[0][0]:
        failures.append(f"Missing or unexpected benchmark variants for {generated}")
    for params, a in variants:
        b = indexed[(jdbc, params)]
        label = f"{generated}{dict(params)}"
        left, right = a["primaryMetric"], b["primaryMetric"]
        if left["scoreUnit"] != right["scoreUnit"]:
            failures.append(f"{label}: incompatible units")
            continue
        ratio = left["score"] / right["score"]
        low, high = left["scoreConfidence"][0], right["scoreConfidence"][1]
        if math.isfinite(low) and math.isfinite(high) and low > high * 1.5:
            failures.append(f"{label}: confidently over 50% slower than equivalent JDBC")
        allocation_key = "gc.alloc.rate.norm"
        allocation_a = next(v["score"] for k,v in a["secondaryMetrics"].items() if k.lstrip("·") == allocation_key)
        allocation_b = next(v["score"] for k,v in b["secondaryMetrics"].items() if k.lstrip("·") == allocation_key)
        if allocation_a > allocation_b * 1.5 + 1024:
            failures.append(f"{label}: excessive extra allocation {allocation_a - allocation_b:.0f} B/op")
        print(f"{label}: timing ratio={ratio:.3f}; allocation={allocation_a:.0f} vs JDBC {allocation_b:.0f} B/op")
build = json.loads((root / "benchmarks/build/reports/build-performance.json").read_text())
for sample in build["samples"]:
    if sample["generationSeconds"] > 10:
        failures.append(f"{sample['queries']} queries: SQL generation exceeded the 10 second CI budget")
    if sample["kotlinCompilationSeconds"] > 60:
        failures.append(f"{sample['queries']} queries: Kotlin compilation exceeded the 60 second CI budget")
    if sample["bootstrapSeconds"] > 30:
        failures.append("Schema bootstrap exceeded the 30 second CI budget")
if failures:
    raise SystemExit("\n".join(failures))
