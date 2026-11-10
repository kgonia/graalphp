"""The call sites in a compilation's machine code, from HotSpot's printed compilation (asmdump.py's input).
Usage: python -I calls.py DUMP [TOP]                      the largest compilation's calls by callee and caller
       python -I calls.py DUMP seq [FROM [COUNT [FRAMES]]] the same calls in address order, with inlining frames
A call site is a {optimized virtual_call}, {virtual_call} or {static_call} mark; its callee and frames are the
*invoke position at its return address, the first one after the mark (the position before a mark is often the
previous call's, so it names the wrong callee)."""
import re
import sys
from collections import Counter

blocks = []
current = None
for line in open(sys.argv[1], encoding='utf-8', errors='replace'):
    if line.startswith('Compiled method'):
        current = []
        blocks.append(current)
    elif current is not None:
        current.append(line)
block = max(blocks, key=len)
sites = []          # [offset, callee, frames]
kinds = Counter()
open_site = None    # the call whose position has not come yet
first = None
address = None
for line in block:
    m = re.match(r'\s+0x([0-9a-f]+):', line)
    if m:
        address = int(m.group(1), 16)
        if first is None:
            first = address
    m = re.search(r'\{(runtime_call[^}]*|static_call|optimized virtual_call|virtual_call)\}', line)
    if m:
        kinds[m.group(1)] += 1
        if 'runtime_call' not in m.group(1):
            open_site = [address - first, None, []]
            sites.append(open_site)
        continue
    if open_site is None:
        continue
    m = re.search(r';\s*\*invoke\w+ (\S+)', line)
    if m:
        if open_site[1] is not None:
            open_site = None
        else:
            open_site[1] = m.group(1)
        continue
    m = re.search(r';\s+-\s+(?:\(reexecute\)\s+)?(\S+)::(\S+?)@(-?\d+)(?: \(line (\d+)\))?', line)
    if m and open_site[1] is not None:
        open_site[2].append(m.group(1).split('.')[-1].split('$')[-1] + '.' + m.group(2) + ':'
                            + (m.group(4) or '@' + m.group(3)))
    elif re.match(r'\s+0x[0-9a-f]+:\s+[0-9a-f]{4}', line) and open_site[1] is not None:
        open_site = None
if len(sys.argv) > 2 and sys.argv[2] == 'seq':
    start = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    count = int(sys.argv[4]) if len(sys.argv) > 4 else 40
    depth = int(sys.argv[5]) if len(sys.argv) > 5 else 3
    for offset, callee, frames in sites[start:start + count]:
        print(f'{offset:7x} {callee!s:16s} {" < ".join(frames[:depth])}')
    print('call sites', len(sites))
else:
    top = int(sys.argv[2]) if len(sys.argv) > 2 else 25
    print('lines', len(block), 'call sites', len(sites), 'kinds', dict(kinds.most_common(6)))
    calls = Counter((callee, frames[0].split(':')[0] if frames else None) for _, callee, frames in sites)
    for (callee, caller), n in calls.most_common(top):
        print(f'{n:6d} {callee!s:28s} in {caller}')
