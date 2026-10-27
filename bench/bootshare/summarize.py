"""Medians of bench/bootshare/run.sh's requests per page: the time at which each phase ends, the bootstrap share
(wp_loaded against the end, the median of the per-request ratios), the peak memory at wp_loaded and at the end,
and curl's time for the whole request. Usage: python summarize.py target/bootshare"""
import json
import statistics
import sys
from collections import defaultdict

PHASES = ['core', 'plugins_loaded', 'after_setup_theme', 'init', 'wp_loaded', 'end']

out = sys.argv[1]
requests = defaultdict(list)
for line in open(f'{out}/log.jsonl'):
    request = json.loads(line)
    requests[request['page']].append(request)
curl = defaultdict(list)
for line in open(f'{out}/curl.txt'):
    page, status, seconds = line.split()
    curl[page].append(float(seconds) * 1000)

med = statistics.median
print(f"{'page':20} {'n':>4} {'status':>6} " + ' '.join(f'{p:>10}' for p in PHASES)
      + f" {'curl':>7} {'boot %':>6} {'boot peak':>9} {'peak':>7}")
for page, rows in requests.items():
    statuses = ','.join(sorted({str(r['status']) for r in rows}))
    times = [med(r['ms'][p] for r in rows) for p in PHASES]
    share = med(r['ms']['wp_loaded'] / r['ms']['end'] for r in rows)
    print(f'{page:20} {len(rows):4} {statuses:>6} ' + ' '.join(f'{t:10.2f}' for t in times)
          + f" {med(curl[page]):7.2f} {share * 100:6.1f} {med(r['boot_peak'] for r in rows) / 2**20:7.1f}MB"
          + f" {med(r['peak'] for r in rows) / 2**20:5.1f}MB")

# the marks after wp_loaded that a page reaches
LATER = ['admin_init', 'rest_api_init', 'wp', 'template_redirect']
print()
for page, rows in requests.items():
    marks = [f'{mark} {med(r["ms"][mark] for r in rows):.2f}' for mark in LATER if mark in rows[0]['ms']]
    print(f'{page:20} ' + ', '.join(marks))

# with run.sh's PROFILE=1: the callbacks and plugin files that take the most of the bootstrap, median per request
try:
    profiles = defaultdict(list)
    for line in open(f'{out}/profile.jsonl'):
        request = json.loads(line)
        profiles[request['page']].append(request['ns'])
except FileNotFoundError:
    profiles = {}
for page, rows in profiles.items():
    keys = {key for row in rows for key in row}
    times = sorted(((med(row.get(key, 0) for row in rows) / 1e6, key) for key in keys), reverse=True)
    boot = med(r['ms']['wp_loaded'] for r in requests[page])
    print(f'\n{page}: wp_loaded at {boot:.2f} ms; timed {sum(t for t, _ in times):.2f} ms in {len(times)} entries')
    for ms, key in times[:30]:
        print(f'  {ms:7.2f} ms {ms / boot * 100:5.1f}%  {key}')
