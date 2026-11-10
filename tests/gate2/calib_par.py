"""calib_par.py NAME...: calib.php run on several CPUs at once (target/fc/calib/NAME-CPU.json), one row per NAME.

Per group: the fast mode's level (first decile of a chunk, ns per iteration), the mean, the iterations per second
of one CPU, the share of slow chunks (> 1.4x the group's lowest first decile) and of the time in them, and what the
trace does to 20 ms of fast-mode work (a hot render's size): its time over the fast level, first decile / median /
mean / 90th percentile.
"""
import glob
import json
import statistics
import sys

FAST = 1.315  # ns per iteration in the fast mode, the same in every run of the night


def load(path):
    d = json.load(open(path))
    return d['n'], [c[1] for c in d['chunks']], d['chunks']


def render(n, ch, work_ns=20e6):
    k = max(1, round(work_ns / FAST / n))
    t = sorted(sum(ch[i:i + k]) / (k * n) / FAST for i in range(0, len(ch) - k + 1, k))
    return t[len(t) // 10], t[len(t) // 2], statistics.mean(t), t[len(t) * 9 // 10]


def rng(xs, fmt='%.3f'):
    lo, hi = min(xs), max(xs)
    return fmt % lo if fmt % lo == fmt % hi else (fmt + '–' + fmt) % (lo, hi)


print('| name | CPUs | first decile, ns/it | mean, ns/it | M it/s per CPU | slow chunks | slow time '
      '| 20 ms of work: p10 / median / mean / p90 |')
print('|---|---|---|---|---|---|---|---|')
for name in sys.argv[1:]:
    files = sorted(glob.glob('target/fc/calib/%s-[0-9]*.json' % name))
    p10s, means, rates, slow_c, slow_t, sims = [], [], [], [], [], []
    for f in files:
        n, ch, raw = load(f)
        s = sorted(ch)
        p10s.append(s[len(s) // 10] / n)
        means.append(statistics.mean(ch) / n)
        span = (raw[-1][0] + raw[-1][1] - raw[0][0]) / 1e9
        rates.append(n * len(ch) / span / 1e6)
        limit = 1.4 * FAST * n
        slow_c.append(100 * sum(1 for c in ch if c > limit) / len(ch))
        slow_t.append(100 * sum(c for c in ch if c > limit) / sum(ch))
        sims.append(render(n, ch))
    cpus = ','.join(f.rsplit('-', 1)[1].split('.')[0] for f in files)
    sim = ' / '.join(rng([s[i] for s in sims], '%.2f') for i in range(4))
    print('| %s | %s | %s | %s | %s | %s%% | %s%% | %s |' % (
        name, cpus, rng(p10s), rng(means), rng(rates, '%.0f'), rng(slow_c, '%.0f'), rng(slow_t, '%.0f'), sim))
