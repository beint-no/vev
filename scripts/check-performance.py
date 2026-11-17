#!/usr/bin/env python3
"""Paired same-process-workload evidence; shared CI runners cannot promise production latency."""
import json
import math
from pathlib import Path

root = Path(__file__).resolve().parents[1]
results = json.loads((root / "benchmarks/build/results/jmh/results.json").read_text())
failures = []
for result in results:
    metric = result["primaryMetric"]
    samples = metric["rawData"]
    if len(samples) != result["forks"] or any(len(fork) != result["measurementIterations"] for fork in samples):
        failures.append(f"Incomplete benchmark forks/iterations: {result['benchmark']}")
    if not math.isfinite(metric["score"]) or metric["score"] <= 0:
        failures.append(f"Invalid benchmark timing: {result['benchmark']}")
indexed = {(r["benchmark"].rsplit(".", 1)[-1], tuple(sorted(r.get("params", {}).items()))): r for r in results}
pairs = [("generated", "jdbc"), ("generatedLedger", "jdbcLedger"),
         ("generatedPage", "jdbcPage"), ("generatedInsert", "jdbcInsert"), ("generatedBulk", "jdbcBulk"),
         ("singleRow", "legacyListSingleRow"), ("generatedSettingsSave", "jdbcSettingsSave")]
for generated, jdbc in pairs:
    variants = [(params, value) for (name, params), value in indexed.items() if name == generated]
    expected_rows = {"generatedPage": {"1", "100", "600"}, "generatedBulk": {"1", "32", "512"},
                     "singleRow": {"0", "1"}}
    if generated == "generatedSettingsSave":
        expected = {(operation, value) for operation in ("insert", "update", "unchanged") for value in ("null", "12.50")}
        actual = {(dict(params).get("operation"), dict(params).get("value")) for params, _ in variants}
        if actual != expected:
            failures.append(f"Missing settings write variants: {actual}")
    elif generated in expected_rows:
        actual_rows = {dict(params).get("rows") for params, _ in variants}
        if actual_rows != expected_rows[generated]:
            failures.append(f"Missing or unexpected parameter variants for {generated}: {actual_rows}")
    elif len(variants) != 1 or variants[0][0]:
        failures.append(f"Missing or unexpected benchmark variants for {generated}")
    for params, a in variants:
        b = indexed[(jdbc, params)]
        label = f"{generated}{dict(params)}"
        baseline = "Vev 2.0 list path" if jdbc == "legacyListSingleRow" else "JDBC"
        left, right = a["primaryMetric"], b["primaryMetric"]
        if left["scoreUnit"] != right["scoreUnit"]:
            failures.append(f"{label}: incompatible units")
            continue
        ratio = left["score"] / right["score"]
        low, high = left["scoreConfidence"][0], right["scoreConfidence"][1]
        if math.isfinite(low) and math.isfinite(high) and low > high * 1.5:
            failures.append(f"{label}: confidently over 50% slower than equivalent {baseline}")
        allocation_key = "gc.alloc.rate.norm"
        allocation_a = next(v["score"] for k,v in a["secondaryMetrics"].items() if k.lstrip("·") == allocation_key)
        allocation_b = next(v["score"] for k,v in b["secondaryMetrics"].items() if k.lstrip("·") == allocation_key)
        if allocation_a > allocation_b * 1.5 + 1024:
            failures.append(f"{label}: excessive extra allocation {allocation_a - allocation_b:.0f} B/op")
        print(f"{label}: timing ratio={ratio:.3f}; allocation={allocation_a:.0f} vs {baseline} {allocation_b:.0f} B/op")
# Hibernate is an optimized native DTO baseline, not an entity-graph straw man.
for name in ("vevNative", "hibernateNative"):
    if (name, ()) not in indexed:
        failures.append(f"Missing Hibernate comparison: {name}")
hibernate_settings = {tuple(sorted({"operation": operation, "value": value}.items()))
                      for operation in ("insert", "update", "unchanged") for value in ("null", "12.50")}
if {params for name, params in indexed if name == "hibernateSettingsSave"} != hibernate_settings:
    failures.append("Missing Hibernate settings entity-save variants")
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
