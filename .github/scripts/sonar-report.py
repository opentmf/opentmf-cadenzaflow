#!/usr/bin/env python3
"""Render the SonarCloud mini report from two API payloads.

Usage:
    sonar-report.py <project_status.json> <measures.json> <project_key> <branch>

Reads `api/qualitygates/project_status` and `api/measures/component` responses and
writes Markdown to stdout. Two things the plain OK/ERROR line does not tell you and
this report does: WHICH gate conditions failed (with actual vs. threshold), and the
NEW-CODE numbers, which are the ones the zero-new-issues rule is judged on.

Exit status is always 0 - gating is the caller's decision, so the report still gets
rendered (and read) on a red gate.
"""

import json
import sys

RATINGS = {"1.0": "A", "2.0": "B", "3.0": "C", "4.0": "D", "5.0": "E"}

# metric key -> (label, is_new_code)
METRICS = [
    ("security_rating", "Security rating", False),
    ("reliability_rating", "Reliability rating", False),
    ("sqale_rating", "Maintainability rating", False),
    ("vulnerabilities", "Vulnerabilities", False),
    ("bugs", "Bugs", False),
    ("code_smells", "Code smells", False),
    ("security_hotspots", "Security hotspots", False),
    ("coverage", "Coverage %", False),
    ("duplicated_lines_density", "Duplicated lines %", False),
    ("new_coverage", "Coverage % (new code)", True),
    ("new_violations", "New issues (new code)", True),
    ("new_duplicated_lines_density", "Duplicated lines % (new code)", True),
]


def measure_values(measures):
    """Flatten the measures payload; new-code metrics carry their value in `period`."""
    values = {}
    for measure in measures.get("component", {}).get("measures", []):
        value = measure.get("value")
        if value is None:
            value = measure.get("period", {}).get("value")
        values[measure["metric"]] = value
    return values


def pretty(metric, value):
    if value is None:
        return "n/a"
    if metric.endswith("_rating"):
        return RATINGS.get(value, value)
    return value


def main():
    # The report carries ✅/❌; don't inherit a legacy console encoding.
    sys.stdout.reconfigure(encoding="utf-8")
    status_path, measures_path, project_key, branch = sys.argv[1:5]
    with open(status_path, encoding="utf-8") as handle:
        project_status = json.load(handle)["projectStatus"]
    with open(measures_path, encoding="utf-8") as handle:
        values = measure_values(json.load(handle))

    status = project_status.get("status", "UNKNOWN")
    icon = "✅" if status == "OK" else "❌"
    dashboard = f"https://sonarcloud.io/dashboard?id={project_key}&branch={branch}"

    print(f"## {icon} SonarCloud quality gate: **{status}**")
    print()
    print(f"[{project_key} on branch `{branch}`]({dashboard})")
    print()

    failed = [c for c in project_status.get("conditions", []) if c.get("status") != "OK"]
    if failed:
        print("### Failed conditions")
        print()
        print("| Metric | Actual | Comparator | Threshold |")
        print("|---|---|---|---|")
        for condition in failed:
            print(
                "| `{metricKey}` | {actualValue} | {comparator} | {errorThreshold} |".format(
                    metricKey=condition.get("metricKey", "?"),
                    actualValue=condition.get("actualValue", "?"),
                    comparator=condition.get("comparator", "?"),
                    errorThreshold=condition.get("errorThreshold", "?"),
                )
            )
        print()

    for heading, new_code in (("Overall", False), ("New code", True)):
        print(f"### {heading}")
        print()
        print("| Measure | Value |")
        print("|---|---|")
        for metric, label, is_new in METRICS:
            if is_new == new_code:
                print(f"| {label} | {pretty(metric, values.get(metric))} |")
        print()


if __name__ == "__main__":
    main()
