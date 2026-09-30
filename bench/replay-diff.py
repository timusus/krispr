#!/usr/bin/env python3
"""Diff-mode replay for docs/evidence.md: what diff mode would have reported on real merged changes.

Usage: replay-diff.py <target> <checkout> <module> <out dir> <commit>...

For each commit C (a merged PR, squashed): check out C, apply Krispr, and run
`krisprRun -Pkrispr.diffBase=C^`, which mutates only the lines C changed, with C's own tests. Records the
end-to-end time (instrumented build included), the counts and the survivors diff mode would post.
"""
import json
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def sh(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True).stdout


def run(target, checkout, module, out, commit):
    dest = os.path.join(out, commit)
    os.makedirs(dest, exist_ok=True)
    sh("git", "checkout", "-q", "-f", commit, cwd=checkout)
    sh("git", "clean", "-fdq", "-e", ".gradle", cwd=checkout)
    sh(os.path.join(HERE, "setup-target.sh"), target, ".", cwd=checkout)
    subject = sh("git", "log", "-1", "--format=%s", commit, cwd=checkout).strip()
    start = time.time()
    ok = subprocess.run(
        [os.path.join(HERE, "gradle-retry.sh"), os.path.join(dest, "krispr.log"), f":{module}:krisprRun",
         f"-Pkrispr.diffBase={commit}^", "-Pkrispr.history=false", "--console=plain"],
        cwd=checkout,
    ).returncode == 0
    # Each revision may bring its own Gradle and Kotlin versions, and idle daemons of each add up past the
    # container's memory.
    subprocess.run(["./gradlew", "--stop"], cwd=checkout, capture_output=True)
    subprocess.run(["pkill", "-f", "KotlinCompileDaemon"], capture_output=True)
    seconds = round(time.time() - start)
    report = os.path.join(checkout, module, "build/krispr/report.json")
    if not ok or not os.path.exists(report):
        return {"commit": commit, "subject": subject, "outcome": "build or run failed", "seconds": seconds}
    shutil.copy(report, os.path.join(dest, "report.json"))
    comment = os.path.join(checkout, module, "build/krispr/pr-summary.md")
    if os.path.exists(comment):
        shutil.copy(comment, os.path.join(dest, "pr-summary.md"))
    summary = json.load(open(report))["summary"]
    return {"commit": commit, "subject": subject, "outcome": "ran", "seconds": seconds, "summary": summary}


def main():
    target, checkout, module, out, *commits = sys.argv[1:]
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, "results.json")
    # Resumable: keep finished commits, redo the ones whose build or run failed.
    done = {r["commit"]: r for r in (json.load(open(path)) if os.path.exists(path) else []) if r["outcome"] != "build or run failed"}
    results = []
    for c in commits:
        if c in done:
            results.append(done[c])
            continue
        r = run(target, checkout, module, out, c)
        s = r.get("summary", {})
        print(json.dumps({"commit": c, "outcome": r["outcome"], "seconds": r["seconds"], "mutants": s.get("total"),
                          "survived": s.get("SURVIVED")}), flush=True)
        results.append(r)
        json.dump(results, open(path, "w"), indent=1)


if __name__ == "__main__":
    main()
