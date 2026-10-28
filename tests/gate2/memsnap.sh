#!/usr/bin/env bash
# One memory snapshot of a running Truffle PHP server (started with -XX:NativeMemoryTracking=summary): the process's
# RssAnon, the cgroup's anon, the NMT summary, the code cache, the live heap after a full GC, and every anonymous
# mapping with its address range, so that tests/gate2/memsplit.py can split the process's memory by owner.
# Usage: tests/gate2/memsnap.sh <container> > snapshot.txt
set -euo pipefail
MSYS_NO_PATHCONV=1 docker exec "$1" sh -c '
  echo "## status"; grep -E "VmRSS|RssAnon|RssFile" /proc/1/status
  echo "## cgroup $(grep "^anon " /sys/fs/cgroup/memory.stat)"; echo "## threads $(ls /proc/1/task | wc -l)"
  echo "## nmt"; $GRAALVM_HOME/bin/jcmd 1 VM.native_memory summary scale=MB
  echo "## codecache"; $GRAALVM_HOME/bin/jcmd 1 Compiler.codecache
  echo "## live heap (after a full GC)"; $GRAALVM_HOME/bin/jcmd 1 GC.run > /dev/null; $GRAALVM_HOME/bin/jcmd 1 GC.heap_info
  echo "## anonymous mappings (start end Rss-kB name)"
  awk "/^[0-9a-f]+-[0-9a-f]+ /{split(\$1,a,\"-\"); start=a[1]; end=a[2]; name=\$6; inode=\$5} /^Rss:/{if(inode==0 && \$2>0) print start, end, \$2, name}" /proc/1/smaps
'
