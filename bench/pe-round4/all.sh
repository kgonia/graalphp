#!/usr/bin/env bash
# wiki/pe-round4.md's runs in order, two queues: timed (CPUs 14-21, one server at a time: Gate 1, the memruns, the
# load A/Bs) and untimed (CPUs 6-13: the Gate 2 pages, the diagnosis servers), each run waiting for its memlock
# reservation. Logs: target/pe4i/QUEUE.log.
# Usage: bench/pe-round4/all.sh timed|untimed
set -uo pipefail
cd "$(dirname "$0")/../.."
mkdir -p target/pe4i
r() { echo "== $(date '+%F %T') $*"; bash bench/pe-round4/runs.sh "$@"; echo "== $(date '+%F %T') done $* ($?)"; }
case ${1:-} in
  timed)
    MEM_TAG=m r mem base t1 2
    r ab base t3 4
    r ab t3 t2 4
    r ab t2 t1 4
    r ab base t1 4
    r gate1 ;;
  untimed)
    r pages
    r diag base d-base splits
    r diag t2 d-t2 splits
    r diag t1 d-t1 splits
    r diag base s-base sampler
    r diag t3 s-t3 sampler
    r diag t1 d-t1 splits ;;
esac
