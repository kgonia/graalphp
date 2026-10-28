"""Splits a Truffle PHP server's anonymous memory (RssAnon) by owner, from a tests/gate2/memsnap.sh snapshot.

Each anonymous mapping is attributed by address: the Java heap and the code cache by the ranges the JVM reports,
glibc's malloc arenas by their 64 MB alignment (and [heap], the main arena), the JVM's other mappings by NMT's
committed mmap memory. Inside the arenas NMT's malloc count is the JVM's share; the rest of the arenas is memory
freed and kept by glibc, plus native libraries' own mallocs (PCRE2, libgraal), which NMT doesn't see. What no rule
claims is mostly libgraal's own heap. The parts add up to RssAnon.

Usage: python tests/gate2/memsplit.py snapshot.txt [--json]
"""
import json
import re
import sys

ARENA = 64 << 20


def parse(text):
    rss_anon = int(re.search(r"RssAnon:\s+(\d+) kB", text).group(1)) * 1024
    nmt = {}
    category = None
    for line in text[text.index("## nmt"):text.index("## codecache")].splitlines():
        head = re.match(r"-\s+(.+?) \(reserved=\d+MB, committed=(\d+)MB", line)
        if head:
            category = head.group(1)
            nmt[category] = {"committed": int(head.group(2)) << 20, "malloc": 0, "mmap": 0}
            continue
        if category:
            malloc = re.search(r"\(malloc=(\d+)MB", line)
            if malloc:
                nmt[category]["malloc"] = int(malloc.group(1)) << 20
            mmap = re.search(r"\(mmap: reserved=\d+MB, committed=(\d+)MB", line)
            if mmap:
                nmt[category]["mmap"] = int(mmap.group(1)) << 20
    malloc_total = int(re.search(r"^\s+malloc: (\d+)MB", text, re.M).group(1)) << 20
    code_ranges = [(int(a, 16), int(c, 16)) for a, _, c in
                   re.findall(r"bounds \[0x([0-9a-f]+), 0x([0-9a-f]+), 0x([0-9a-f]+)\]", text)]
    heap = re.search(r"used (\d+)K \[0x([0-9a-f]+), 0x([0-9a-f]+)\)", text)
    heap_live = int(heap.group(1)) * 1024
    heap_range = (int(heap.group(2), 16), int(heap.group(3), 16))
    mappings = []
    if "## anonymous mappings" in text:
        for line in text[text.index("## anonymous mappings"):].splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 3 and re.fullmatch(r"[0-9a-f]+", parts[0]):
                start, end = int(parts[0], 16), int(parts[1], 16)
                mappings.append((start, end, int(parts[2]) * 1024, parts[3] if len(parts) > 3 else ""))
    else:  # memory.sh's first snapshots: the 40 largest unnamed mappings (Rss kB, size kB, start)
        section = text[text.index("## unnamed"):text.index("## named")]
        for rss, size, start in re.findall(r"^(\d+) (\d+) ([0-9a-f]+)$", section, re.M):
            mappings.append((int(start, 16), int(start, 16) + int(size) * 1024, int(rss) * 1024, ""))
    return rss_anon, nmt, malloc_total, code_ranges, heap_live, heap_range, mappings


def split(text):
    rss_anon, nmt, malloc_total, code_ranges, heap_live, heap_range, mappings = parse(text)

    def within(start, ranges):
        return any(low <= start < high for low, high in ranges)

    heap_rss = code_rss = arena_rss = 0
    for start, end, rss, name in mappings:
        if within(start, [heap_range]):
            heap_rss += rss
        elif within(start, code_ranges):
            code_rss += rss
        elif name == "[heap]" or (start % ARENA == 0 and end - start <= ARENA):
            arena_rss += rss
    code_malloc = nmt.get("Code", {}).get("malloc", 0)
    # the JVM's own mappings besides the heap and the code cache (the class-data archive is a file mapping)
    jvm_mmap = sum(c["mmap"] for name, c in nmt.items() if name not in ("Java Heap", "Code", "Shared class space"))
    live = min(heap_live, heap_rss)
    parts = {
        "heap_live": live,
        "heap_garbage": heap_rss - live,
        "compiled_code": code_rss + code_malloc,
        "jvm_runtime": jvm_mmap + malloc_total - code_malloc,
        "malloc_kept": arena_rss - malloc_total,
    }
    parts["untracked"] = rss_anon - sum(parts.values())
    return {"rss_anon": rss_anon, "parts": parts}


def main():
    result = split(open(sys.argv[1], encoding="utf-8", errors="replace").read())
    if "--json" in sys.argv:
        print(json.dumps({k: round(v / 2 ** 20) for k, v in {"rss_anon": result["rss_anon"], **result["parts"]}.items()}))
        return
    print(f"{'RssAnon':16} {result['rss_anon'] / 2 ** 20:7.0f} MB")
    for name, value in result["parts"].items():
        print(f"  {name:14} {value / 2 ** 20:7.0f} MB")


if __name__ == "__main__":
    main()
