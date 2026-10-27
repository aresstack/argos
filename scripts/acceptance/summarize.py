#!/usr/bin/env python3
"""Summarize Argos JUnit XML results and compare them with a baseline.

  summarize.py RESULTS_DIR OUT.json            write a summary of one run
  summarize.py --compare BASE.json NEW.json    classify the new run's failures

The summary maps each test (class#name) to passed, failed, skipped or missing.
--compare exits 1 when the new run has a regression: a test that passed in the
baseline and fails, or that the baseline ran and the new run no longer reports.
Failures already present in the baseline are listed as pre-existing.
"""
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET


def summarize(results_dir):
    tests = {}
    for path in sorted(glob.glob(os.path.join(results_dir, "TEST-*.xml"))):
        for case in ET.parse(path).getroot().iter("testcase"):
            key = case.get("classname", "") + "#" + case.get("name", "")
            if case.find("failure") is not None or case.find("error") is not None:
                state = "failed"
            elif case.find("skipped") is not None:
                state = "skipped"
            else:
                state = "passed"
            # A retried or repeated name counts as failed if any run failed.
            if tests.get(key) != "failed":
                tests[key] = state
    counts = {s: sum(1 for v in tests.values() if v == s) for s in ("passed", "failed", "skipped")}
    return {"counts": counts, "tests": tests}


def compare(base, new):
    b, n = base["tests"], new["tests"]
    regressions = sorted(k for k, v in n.items() if v == "failed" and b.get(k) == "passed")
    vanished = sorted(k for k, v in b.items() if v in ("passed", "failed") and k not in n)
    known = sorted(k for k, v in n.items() if v == "failed" and b.get(k) == "failed")
    new_failing = sorted(k for k, v in n.items() if v == "failed" and k not in b)
    fixed = sorted(k for k, v in n.items() if v == "passed" and b.get(k) == "failed")
    newly_skipped = sorted(k for k, v in n.items() if v == "skipped" and b.get(k) == "passed")
    print("baseline:", base["counts"], " new:", new["counts"])
    for title, items in (("REGRESSION (passed before, fails now)", regressions),
                         ("REGRESSION (new test fails)", new_failing),
                         ("MISSING (baseline ran it, new run does not)", vanished),
                         ("NOW SKIPPED (passed before)", newly_skipped),
                         ("pre-existing failure", known),
                         ("fixed", fixed)):
        for k in items:
            print("%s: %s" % (title, k))
    return 1 if regressions or new_failing or vanished else 0


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--compare":
        with open(sys.argv[2], encoding="utf-8") as f:
            base = json.load(f)
        with open(sys.argv[3], encoding="utf-8") as f:
            new = json.load(f)
        sys.exit(compare(base, new))
    if len(sys.argv) == 3:
        summary = summarize(sys.argv[1])
        with open(sys.argv[2], "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=1, sort_keys=True)
        print(summary["counts"])
        sys.exit(0)
    sys.exit(__doc__)
