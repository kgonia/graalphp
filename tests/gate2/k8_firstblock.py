"""k8_firstblock.py K8_JSON...: per K8 block of a `native.sh k8` result (target/NAME/k8/VARIANT-rN.k8.json), its seconds,
its CPU seconds and the hot render (iterations 3 and up, ms, median) per ten requests: how a block moves within
itself (issues/103-exit-deopt-cycle.md)."""
import json, os, statistics, sys
for path in sys.argv[1:]:
    d = json.load(open(path))
    for b in d['blocks']:
        if b['kind'] != 'k8':
            continue
        rs = b['requests']
        out = []
        for i in range(0, len(rs), 10):
            hot = [it[0] / 1e6 for r in rs[i:i + 10] if 'iterations' in r for it in r['iterations'][2:]]
            out.append('%.1f' % statistics.median(hot) if hot else '-')
        print('%-26s %-12s %5.1f s cpu %5.1f s | %s' % (os.path.basename(path)[:-len('.k8.json')], b['page'], b['seconds'],
                                                        b['cpu_s'], ' '.join(out)))
