"""The two speeds of calib.php's chunks. Usage: python -I calib.py FILE.json..."""
import json
import statistics
import sys

for path in sys.argv[1:]:
    doc = json.load(open(path, encoding='utf-8'))
    ch = doc['chunks']
    d = [c[1] / 1e6 for c in ch]
    st = [c[0] / 1e9 for c in ch]
    s = sorted(d)
    fast = s[len(s) // 10]            # the fast speed: the first decile
    slow = [x > 1.4 * fast for x in d]
    q = lambda p: s[min(len(s) - 1, int(len(s) * p))]
    print(f'{path}: chunks {len(d)}, {st[-1]:.0f} s; ms p10 {fast:.3f} median {q(.5):.3f} p90 {q(.9):.3f} max {s[-1]:.3f}; '
          f'mean/p10 {statistics.mean(d)/fast:.3f}')
    print(f'  chunks above 1.4x p10: {100*sum(slow)/len(d):.1f}% of chunks, '
          f'{100*sum(x for x, f in zip(d, slow) if f)/sum(d):.1f}% of time')
    runs = []
    i = 0
    while i < len(d):
        if slow[i]:
            j = i
            while j + 1 < len(d) and slow[j + 1]:
                j += 1
            runs.append((st[i], st[j] + d[j] / 1e3 - st[i], statistics.mean(d[i:j + 1]) / fast))
            i = j + 1
        else:
            i += 1
    long = [r for r in runs if r[1] >= 0.02]
    if long:
        gaps = sorted(b[0] - a[0] for a, b in zip(long, long[1:]))
        lens = sorted(r[1] for r in long)
        ratio = sorted(r[2] for r in long)
        print(f'  slow runs of 20 ms and more: {len(long)}; length s median {lens[len(lens)//2]:.3f} p90 {lens[int(len(lens)*.9)]:.3f}; '
              f'slowdown median x{ratio[len(ratio)//2]:.2f}; '
              + (f'gap between starts median {gaps[len(gaps)//2]:.3f} p10 {gaps[len(gaps)//10]:.3f} p90 {gaps[int(len(gaps)*.9)]:.3f} s' if gaps else ''))
    hist = {}
    for x in d:
        b = round(x / fast * 10) / 10
        b = min(b, 3.0)
        hist[b] = hist.get(b, 0) + 1
    print('  time over p10 : chunks  ' + ' '.join(f'{k:.1f}:{v}' for k, v in sorted(hist.items())))
