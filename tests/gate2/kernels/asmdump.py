"""A compiled function's machine code from HotSpot's hex dump, for objdump, and objdump's listing with HotSpot's
source positions and perf's samples put back (wiki/ceiling.md; perfasm.sh runs it all). The JVM runs without a
disassembler (no hsdis), so -XX:CompileCommand=print,*OptimizedCallTarget.profiledPERoot prints each Truffle
compilation as hex with the positions as comments (-XX:+DebugNonSafepoints
-Dpolyglot.compiler.NodeSourcePositions=true -Djdk.graal.TrackNodeSourcePosition=true give a position to the
instructions that can deoptimize or call, few of the rest).

Usage: python -I asmdump.py list DUMP [IPS]           the compilations: index, address, size, samples (hottest first)
       python -I asmdump.py bin DUMP INDEX OUT.bin    writes the code; prints its address for --adjust-vma
       python -I asmdump.py merge DUMP INDEX LISTING [IPS] [FRAMES]
              LISTING: objdump -D -b binary -m i386:x86-64 -M intel --adjust-vma=ADDRESS OUT.bin
              prints each instruction (its samples, its offset) after the positions that start at it, FRAMES
              innermost frames each (default 3); jumps inside the code show their target's offset
IPS: `perf script -F time,ip`. Of the samples in a compilation the later half counts (by then the code around it
has settled). A sample names the instruction the interrupt found waiting, so a slow instruction's time shows on
the one after it.
"""
import bisect
import re
import sys

HEX = re.compile(r'^\s+0x([0-9a-f]+):\s+([0-9a-f |]+?)\s*$')
COMMENT = re.compile(r'^\s+0x([0-9a-f]+):\s+;(.*)$')
MORE = re.compile(r'^\s+;(.*)$')


def blocks(path):
    """Each compilation: (header line, {address: bytes}, {address: [comment lines]})."""
    out = []
    current = None
    last = None
    for line in open(path, encoding='utf-8', errors='replace'):
        if line.startswith('Compiled method'):
            current = (line.strip(), {}, {})
            out.append(current)
            last = None
            continue
        if current is None:
            continue
        m = COMMENT.match(line)
        if m:
            last = int(m.group(1), 16)
            current[2].setdefault(last, []).append(m.group(2).strip())
            continue
        m = MORE.match(line)
        if m and last is not None:
            current[2][last].append(m.group(1).strip())
            continue
        m = HEX.match(line)
        if m:
            current[1][int(m.group(1), 16)] = bytes.fromhex(m.group(2).replace('|', '').replace(' ', ''))
    return out


def image(block):
    code = block[1]
    start = min(code)
    end = max(a + len(b) for a, b in code.items())
    data = bytearray(end - start)
    for a, b in code.items():
        data[a - start:a - start + len(b)] = b
    return start, bytes(data)


def samples(path):
    """The samples as (address, time), by address."""
    seen = []
    for line in open(path, encoding='utf-8', errors='replace'):
        m = re.match(r'\s*([0-9.]+):\s+([0-9a-f]+)\s*$', line)
        if m:
            seen.append((int(m.group(2), 16), float(m.group(1))))
    return sorted(seen)


def within(seen, start, end):
    """The addresses sampled in [start, end), the later half of them by time."""
    inside = seen[bisect.bisect_left(seen, (start, 0.0)):bisect.bisect_left(seen, (end, 0.0))]
    inside.sort(key=lambda sample: sample[1])
    return [ip for ip, _ in inside[len(inside) // 2:]]


def frames(lines, depth):
    """A position's frames, innermost first, as Class.method:line."""
    out = []
    for text in lines:
        m = re.match(r'-\s+(?:\(reexecute\)\s+)?(\S+)::(\S+?)@(-?\d+)(?: \(line (\d+)\))?', text)
        if m:
            cls = m.group(1).split('.')[-1].split('$')[-1]
            out.append(f'{cls}.{m.group(2)}:{m.group(4) or "@" + m.group(3)}')
        elif text.startswith('{') and ('oop(' in text or 'metadata(' in text or 'runtime_call' in text):
            out.append(text)
    return out[:depth]


def called(lines):
    """The method a call returns from, from the position at its return address."""
    for text in lines:
        m = re.match(r'\*invoke\w+ (\S+)', text)
        if m:
            return m.group(1)
    return None


def main():
    mode, dump = sys.argv[1], sys.argv[2]
    all_blocks = blocks(dump)
    if mode == 'list':
        seen = samples(sys.argv[3]) if len(sys.argv) > 3 else []
        rows = []
        for i, block in enumerate(all_blocks):
            if block[1]:
                start, data = image(block)
                rows.append((len(within(seen, start, start + len(data))), i, start, len(data), block[0]))
        rows.sort(key=lambda row: (-row[0], row[1]))
        for count, i, start, size, header in rows:
            print(i, hex(start), size, count, header[:70])
        return
    block = all_blocks[int(sys.argv[3])]
    start, data = image(block)
    if mode == 'bin':
        open(sys.argv[4], 'wb').write(data)
        print(hex(start))
        return
    rest = sys.argv[5:]
    ips = []
    if rest and not rest[0].isdigit():
        ips = within(samples(rest.pop(0)), start, start + len(data))
    depth = int(rest[0]) if rest else 3
    counts = {}
    for ip in ips:
        counts[ip] = counts.get(ip, 0) + 1
    ins = re.compile(r'^\s*([0-9a-f]+):\s+(?:[0-9a-f]{2} )+\s*(.*)$')
    listing = []
    for line in open(sys.argv[4], encoding='utf-8', errors='replace'):
        m = ins.match(line)
        if m and m.group(2):
            listing.append((int(m.group(1), 16), m.group(2)))
    # a sample between two listed addresses (an instruction's continuation line) is its instruction's
    addresses = [address for address, _ in listing]
    per = [0] * len(listing)
    for ip, count in counts.items():
        per[max(bisect.bisect_right(addresses, ip) - 1, 0)] += count
    if ips:
        print(f'samples: {len(ips)}')
    shown = None
    for n, (address, text) in enumerate(listing):
        if address in block[2]:
            where = ' < '.join(frames(block[2][address], depth))
            if where and where != shown:
                print(f'              ; {where}')
                shown = where

        def local(m):
            target = int(m.group(0), 16)
            return f'<{target - start:x}>' if start <= target < start + len(data) else m.group(0)

        text = re.sub(r'0x[0-9a-f]{8,}', local, text)
        if text.startswith('call') and n + 1 < len(listing):
            name = called(block[2].get(listing[n + 1][0], []))
            if name:
                text += f'    -> {name}'
        mark = f'{per[n]:6d}' if per[n] else '      '
        print(f'{mark} {address - start:5x}: {text}')


main()
