#!/usr/bin/env python3
"""Paired same-process-workload evidence; shared CI runners cannot promise production latency."""
import json
import math
from pathlib import Path

root = Path(__file__).resolve().parents[1]
results = json.loads((root / "benchmarks/build/results/jmh/results.json").read_text())
indexed = {r["benchmark"].rsplit(".", 1)[-1]: r for r in results}
failures = []
for generated, jdbc in [("generated", "jdbc"), ("generatedLedger", "jdbcLedger")]:
    a, b = indexed[generated], indexed[jdbc]
    left, right = a["primaryMetric"], b["primaryMetric"]
    if left["scoreUnit"] != right["scoreUnit"]:
        failures.append(f"{generated}: incompatible units")
        continue
    ratio = left["score"] / right["score"]
    # Fail timing only for a substantial separation of confidence intervals.
    low, high = left["scoreConfidence"][0], right["scoreConfidence"][1]
    if math.isfinite(low) and math.isfinite(high) and low > high * 1.5:
        failures.append(f"{generated}: confidently over 50% slower than equivalent JDBC")
    allocation_key = "gc.alloc.rate.norm"
    allocation_a = next(v["score"] for k,v in a["secondaryMetrics"].items() if k.lstrip("·") == allocation_key)
    allocation_b = next(v["score"] for k,v in b["secondaryMetrics"].items() if k.lstrip("·") == allocation_key)
    if allocation_a > allocation_b * 1.5 + 1024:
        failures.append(f"{generated}: excessive extra allocation {allocation_a - allocation_b:.0f} B/op")
    print(f"{generated}: timing ratio={ratio:.3f}; allocation={allocation_a:.0f} vs JDBC {allocation_b:.0f} B/op")
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
