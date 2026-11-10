"""The source positions of the largest compilation's machine code in address order, one line per mark:
offset, the bytecode at the mark, FRAMES innermost frames. Usage: python -I marks.py DUMP FROM TO [FRAMES]
(offsets in hex, from the compilation's first instruction)."""
import re
import sys

blocks = []
current = None
for line in open(sys.argv[1], encoding='utf-8', errors='replace'):
    if line.startswith('Compiled method'):
        current = []
        blocks.append(current)
    elif current is not None:
        current.append(line)
block = max(blocks, key=len)
start, end = int(sys.argv[2], 16), int(sys.argv[3], 16)
depth = int(sys.argv[4]) if len(sys.argv) > 4 else 2
first = None
offset = None
entry = None
out = []


def flush():
    if entry and start <= entry[0] <= end:
        out.append(f'{entry[0]:6x} {entry[1]:28s} {" < ".join(entry[2][:depth])}')


for line in block:
    m = re.match(r'\s+0x([0-9a-f]+):', line)
    if m:
        address = int(m.group(1), 16)
        if first is None:
            first = address
        offset = address - first
    if offset is None:
        continue
    m = re.search(r';\s*(\*\w+(?: \w+)?|\{[^}]*\}|implicit exception)', line)
    if m and not re.search(r';\s+-\s', line):
        flush()
        entry = [offset, m.group(1)[:28], []]
        continue
    m = re.search(r';\s+-\s+(?:\(reexecute\)\s+)?(\S+)::(\S+?)@(-?\d+)(?: \(line (\d+)\))?', line)
    if m and entry is not None:
        cls = m.group(1).split('.')[-1].split('$')[-1]
        entry[2].append(f'{cls}.{m.group(2)}:{m.group(4) or "@" + m.group(3)}')
flush()
last = None
for row in out:
    if row[7:] != last:
        print(row)
    last = row[7:]
