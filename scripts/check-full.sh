#!/usr/bin/env bash
# Full tier, pre-merge: every compiler variant (k2120, k220, k230, k2320, k240), runtime and Gradle-plugin
# unit tests, krispr-gradle's functionalTest (Gradle TestKit builds of sample Android/KMP projects), and
# scripts/verify-clean-build.sh. ~1 min warm, ~8-12 min cold. Run scripts/check-fast.sh for everyday
# iteration instead.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
"$root/gradlew" --max-workers=4 check
"$root/scripts/verify-clean-build.sh"
