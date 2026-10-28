#!/usr/bin/env bash
# Run the full Argos suite against real Camoufox and keep a JSON summary.
#   scripts/acceptance/run-suite.sh LABEL [extra gradle args...]
# Uses Argos' managed Camoufox (BROWSER_DIR) and optionally BROWSER_LAUNCH_CONFIG (see docs/acceptance.md).
# Writes build/acceptance/LABEL/{summary.json,test-results/,gradle.log}.
set -u
label=${1:?label, e.g. baseline-main or integration-<sha>}; shift
cd "$(dirname "$0")/../.."
out=build/acceptance/$label
mkdir -p "$out"
{ echo "commit $(git rev-parse HEAD)"; git status --short; java -version 2>&1 | head -1
  echo "browser dir ${BROWSER_DIR:-~/.argos/browser}"; echo "launch config ${BROWSER_LAUNCH_CONFIG:-built-in}"; echo "args $*"; } > "$out/environment.txt"
bash gradlew clean test --continue -PtestCamoufox=true -PexpectUnicodeKeys=true "$@" 2>&1 | tee "$out/gradle.log"
status=${PIPESTATUS[0]}
rm -rf "$out/test-results" && cp -r build/test-results/test "$out/test-results"
python3 scripts/acceptance/summarize.py "$out/test-results" "$out/summary.json" || python scripts/acceptance/summarize.py "$out/test-results" "$out/summary.json"
echo "gradle exit $status; summary in $out/summary.json"
exit "$status"
