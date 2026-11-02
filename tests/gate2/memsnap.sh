#!/usr/bin/env bash
# One memory snapshot of a running Truffle PHP server (started with -XX:NativeMemoryTracking=summary): the process's
# RssAnon, the cgroup's anon, the NMT summary, the code cache, the live heap after a full GC with its class histogram,
# and every anonymous mapping with its address range, so that tests/gate2/memsplit.py can split the process's memory
# by owner.
# NOGC=1: no full GC and no class histogram (snapshots during a load run, which a full GC would disturb): the heap
# as it stands. NATIVE=1: a native image (no jcmd): the status and the mappings only.
# Usage: tests/gate2/memsnap.sh <container> > snapshot.txt
set -euo pipefail
MSYS_NO_PATHCONV=1 docker exec -e NOGC="${NOGC:-}" -e NATIVE="${NATIVE:-}" "$1" sh -c '
  echo "## status"; grep -E "VmRSS|RssAnon|RssFile|RssShmem" /proc/1/status
  echo "## cgroup $(grep "^anon " /sys/fs/cgroup/memory.stat)"; echo "## threads $(ls /proc/1/task | wc -l)"
  # CPU seconds so far of the threads by name (the first 15 characters), Truffle'"'"'s compiler threads among them
  echo "## thread cpu (name count seconds)"
  for t in /proc/1/task/*; do echo "$(cat $t/comm 2>/dev/null | tr -d "0-9 " )|$(cut -d")" -f2 $t/stat 2>/dev/null | cut -d" " -f13,14)"; done \
    | awk -F"|" "{split(\$2,c,\" \"); n[\$1]++; s[\$1]+=c[1]+c[2]} END {for (k in n) printf \"%s %d %.1f\\n\", k, n[k], s[k]/100}"
  if [ -z "$NATIVE" ]; then
    echo "## nmt"; $GRAALVM_HOME/bin/jcmd 1 VM.native_memory summary scale=MB
    echo "## codecache"; $GRAALVM_HOME/bin/jcmd 1 Compiler.codecache
    if [ -z "$NOGC" ]; then
      echo "## live heap (after a full GC)"; $GRAALVM_HOME/bin/jcmd 1 GC.run > /dev/null
      $GRAALVM_HOME/bin/jcmd 1 GC.heap_info
      echo "## class histogram (live objects)"; $GRAALVM_HOME/bin/jcmd 1 GC.class_histogram
    else
      echo "## live heap (no GC: as it stands)"; $GRAALVM_HOME/bin/jcmd 1 GC.heap_info
    fi
  fi
  echo "## anonymous mappings (start end Rss-kB name)"
  awk "/^[0-9a-f]+-[0-9a-f]+ /{split(\$1,a,\"-\"); start=a[1]; end=a[2]; name=\$6; inode=\$5} /^Rss:/{if(inode==0 && \$2>0) print start, end, \$2, name}" /proc/1/smaps
'
