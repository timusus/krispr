#!/usr/bin/env python3
"""Turns the survivors diff mode would post on each replayed commit into items for rating.

Usage: diff-items.py <out items.jsonl> <target>|<repo clone>|<module>|<replay results.json> ...

For each commit with survivors, adds a git worktree of the commit under <repo clone>-wt/<commit> (the
source the survivor is on) and writes one item per survivor, at most 20 per commit: the cap the PR comment posts.
"""
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from sample import context  # noqa: E402

CAP = 20


def main():
    out, specs = sys.argv[1], sys.argv[2:]
    n = 0
    with open(out, "w") as f:
        for spec in specs:
            target, clone, module, results = spec.split("|")
            for r in json.load(open(results)):
                if r["outcome"] != "ran" or not r["summary"].get("SURVIVED"):
                    continue
                c = r["commit"]
                wt = f"{clone}-wt/{c}"
                if not os.path.isdir(wt):
                    subprocess.run(["git", "-C", clone, "worktree", "add", "-f", "--detach", wt, c], check=True, capture_output=True)
                report = json.load(open(os.path.join(os.path.dirname(results), c, "report.json")))
                survivors = [m for m in report["mutants"] if m["status"] == "SURVIVED"][:CAP]
                for m in survivors:
                    path = os.path.join(wt, module, m["file"])
                    f.write(json.dumps({
                        "id": f"D{n:03}", "target": target, "commit": c, "checkout": wt,
                        "file": os.path.relpath(path, wt), "line": m["line"], "mutation": m["description"],
                        "source": context(path, m["line"]),
                    }) + "\n")
                    n += 1
    print(f"{n} items")


if __name__ == "__main__":
    main()
