#!/usr/bin/env bash
# Model-checks SharedEngine.tla with TLC (wiki/tla-engine.md).
# Usage: models/engine/run.sh [config...]   (default: SharedEngine.cfg, the server with the runtime as fixed).
# Bound.cfg is a bound engine; BeforeHits.cfg, BeforeClose.cfg (the runtime as it was) and NaiveKey.cfg (a wrong
# fix) are the counterexamples, so TLC reports a violated invariant there. TLA2TOOLS is the tla2tools.jar,
# TLC_HEAP its heap (2g).
set -euo pipefail
cd "$(dirname "$0")"
java="${GRAALVM_HOME:-$HOME/.jdks/graalvm-25.4.4.1.1+1.1}/bin/java"
jar="${TLA2TOOLS:-$HOME/tools/tla2tools.jar}"
meta="${TMPDIR:-/tmp}/tlc-shared-engine"
configs=("$@")
[ ${#configs[@]} -eq 0 ] && configs=(SharedEngine.cfg)
status=0
for config in "${configs[@]}"; do
  echo "== $config"
  # -deadlock: a run ends with every request closed, which isn't a deadlock
  "$java" -XX:+UseParallelGC "-Xmx${TLC_HEAP:-2g}" -cp "$jar" tlc2.TLC -workers 4 -deadlock \
    -metadir "$meta/$(date +%s%N)" \
    -config "$config" SharedEngine.tla || status=$?
done
exit $status
